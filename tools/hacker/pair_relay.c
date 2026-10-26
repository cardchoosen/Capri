#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <poll.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* Fixed, developer-only fixture scope. No gateway changes, capture files, or HTTPS processing. */
#define DIR "/data/local/tmp/capri-pair-relay"
#define SELF "192.168.1.8"
#define TARGET "192.168.1.10"
#define SERVER "192.168.1.16"
struct context { unsigned char magic[8];int index;uint32_t self,target,server;unsigned char own[6],client[6],backend[6]; };
struct counters { atomic_ulong forwarded,fixture_tcp,fixture_get,errors; };
static volatile sig_atomic_t stop_now;
static void stopped(int value) { (void)value;stop_now=1; }
static long long now_ms(void) { struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (long long)t.tv_sec*1000+t.tv_nsec/1000000; }
static void delay(int ms) { struct timespec t={ms/1000,(ms%1000)*1000000};nanosleep(&t,NULL); }
static int directory(void) {
    if(geteuid()!=0)return -1;
    if(mkdir(DIR,0700) && errno!=EEXIST)return -1;
    int fd=open(DIR,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);struct stat s;
    if(fd<0)return -1;
    if(fstat(fd,&s)||s.st_uid!=0||(s.st_mode&0777)!=0700){close(fd);return -1;}
    return fd;
}
static int local_file(int dir,const char *name,int flags) {
    int fd=openat(dir,name,flags|O_NOFOLLOW|O_CLOEXEC,0600);struct stat s;
    if(fd<0)return -1;
    if(fstat(fd,&s)||!S_ISREG(s.st_mode)||s.st_uid!=0||s.st_nlink!=1){close(fd);return -1;}
    return fd;
}
static int neighbor(int fd,uint32_t ip,unsigned char mac[6]) {
    struct arpreq r;memset(&r,0,sizeof(r));
    struct sockaddr_in *p=(struct sockaddr_in*)&r.arp_pa;p->sin_family=AF_INET;p->sin_addr.s_addr=ip;
    strcpy(r.arp_dev,"wlan0");
    if(ioctl(fd,SIOCGARP,&r)||!(r.arp_flags&ATF_COM))return -1;
    memcpy(mac,r.arp_ha.sa_data,6);return 0;
}
static int context(struct context *c) {
    memset(c,0,sizeof(*c));memcpy(c->magic,"CPRPAIR1",8);
    inet_pton(AF_INET,SELF,&c->self);inet_pton(AF_INET,TARGET,&c->target);inet_pton(AF_INET,SERVER,&c->server);
    c->index=(int)if_nametoindex("wlan0");if(c->index<1)return -1;
    int fd=socket(AF_INET,SOCK_DGRAM|SOCK_CLOEXEC,0);if(fd<0)return -1;
    struct ifreq r;memset(&r,0,sizeof(r));strcpy(r.ifr_name,"wlan0");
    int result=-1;
    if(ioctl(fd,SIOCGIFADDR,&r)||((struct sockaddr_in*)&r.ifr_addr)->sin_addr.s_addr!=c->self)goto done;
    if(ioctl(fd,SIOCGIFHWADDR,&r))goto done;
    memcpy(c->own,r.ifr_hwaddr.sa_data,6);
    if(neighbor(fd,c->target,c->client)||neighbor(fd,c->server,c->backend))goto done;
    if(!memcmp(c->own,c->client,6)||!memcmp(c->own,c->backend,6)||!memcmp(c->client,c->backend,6))goto done;
    result=0;
done:close(fd);return result;
}
static int matching(const struct context *c) { struct context current;return !context(&current)&&!memcmp(c,&current,sizeof(current)); }
static int arp_socket(const struct context *c) {
    int fd=socket(AF_PACKET,SOCK_RAW|SOCK_CLOEXEC,0);if(fd<0)return -1;
    struct sockaddr_ll a;memset(&a,0,sizeof(a));a.sll_family=AF_PACKET;a.sll_ifindex=c->index;
    if(bind(fd,(struct sockaddr*)&a,sizeof(a))){close(fd);return -1;}return fd;
}
static int announce(int fd,const struct context *c,int restore) {
    unsigned char frame[42]={0};
    memcpy(frame,c->client,6);memcpy(frame+6,c->own,6);frame[12]=8;frame[13]=6;
    frame[15]=1;frame[16]=8;frame[18]=6;frame[19]=4;frame[21]=2;
    memcpy(frame+22,restore?c->backend:c->own,6);memcpy(frame+28,&c->server,4);
    memcpy(frame+32,c->client,6);memcpy(frame+38,&c->target,4);
    struct sockaddr_ll a;memset(&a,0,sizeof(a));a.sll_family=AF_PACKET;a.sll_ifindex=c->index;a.sll_protocol=htons(ETH_P_ARP);a.sll_halen=6;memcpy(a.sll_addr,c->client,6);
    return sendto(fd,frame,sizeof(frame),0,(struct sockaddr*)&a,sizeof(a))==(ssize_t)sizeof(frame)?0:-1;
}
static int restore_neighbor(int fd,const struct context *c) {
    if(!matching(c))return -1;
    int failures=0;for(int i=0;i<4;i++){if(announce(fd,c,1))failures++;delay(60);}return failures?-1:0;
}
static uint32_t word(const unsigned char *m) { return (uint32_t)m[0]<<24|(uint32_t)m[1]<<16|(uint32_t)m[2]<<8|m[3]; }
static uint32_t half(const unsigned char *m) { return (uint32_t)m[0]<<8|m[1]; }
static int receiver(const struct context *c) {
    int fd=socket(AF_PACKET,SOCK_RAW|SOCK_NONBLOCK|SOCK_CLOEXEC,htons(ETH_P_IP));if(fd<0)return -1;
    struct sock_filter f[]={
        BPF_STMT(BPF_LD|BPF_H|BPF_ABS,12),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,ETH_P_IP,1,0),BPF_STMT(BPF_RET|BPF_K,0),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,0),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,word(c->own),1,0),BPF_STMT(BPF_RET|BPF_K,0),
        BPF_STMT(BPF_LD|BPF_H|BPF_ABS,4),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,half(c->own+4),1,0),BPF_STMT(BPF_RET|BPF_K,0),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,6),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,word(c->client),1,0),BPF_STMT(BPF_RET|BPF_K,0),
        BPF_STMT(BPF_LD|BPF_H|BPF_ABS,10),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,half(c->client+4),1,0),BPF_STMT(BPF_RET|BPF_K,0),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,26),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,ntohl(c->target),1,0),BPF_STMT(BPF_RET|BPF_K,0),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,30),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,ntohl(c->server),1,0),BPF_STMT(BPF_RET|BPF_K,0),
        BPF_STMT(BPF_RET|BPF_K,65550)
    };
    struct sock_fprog p={(unsigned short)(sizeof(f)/sizeof(f[0])),f};
    struct sockaddr_ll a;memset(&a,0,sizeof(a));a.sll_family=AF_PACKET;a.sll_ifindex=c->index;a.sll_protocol=htons(ETH_P_IP);
    if(setsockopt(fd,SOL_SOCKET,SO_ATTACH_FILTER,&p,sizeof(p))||bind(fd,(struct sockaddr*)&a,sizeof(a))){close(fd);return -1;}
    return fd;
}
static int relay_one(int fd,const struct context *c,struct counters *s) {
    unsigned char frame[65550];struct sockaddr_ll from;socklen_t size=sizeof(from);
    ssize_t length=recvfrom(fd,frame,sizeof(frame),0,(struct sockaddr*)&from,&size);
    if(length<0)return errno==EAGAIN||errno==EINTR?0:-1;
    if(from.sll_pkttype==PACKET_OUTGOING||length<34||memcmp(frame,c->own,6)||memcmp(frame+6,c->client,6)||memcmp(frame+26,&c->target,4)||memcmp(frame+30,&c->server,4))return 0;
    unsigned header=(frame[14]&15u)*4u;
    if(frame[23]==6 && header>=20 && length>=(ssize_t)(14+header+20) && !(frame[20]&0x3f) && !frame[21]) {
        unsigned char *tcp=frame+14+header;
        if(half(tcp+2)==18765) {
            atomic_fetch_add(&s->fixture_tcp,1);
            unsigned data=(tcp[12]>>4)*4u;
            if(data>=20 && length>=(ssize_t)(14+header+data+4) && !memcmp(tcp+data,"GET ",4))atomic_fetch_add(&s->fixture_get,1);
        }
    }
    memcpy(frame,c->backend,6);memcpy(frame+6,c->own,6);
    struct sockaddr_ll a;memset(&a,0,sizeof(a));a.sll_family=AF_PACKET;a.sll_ifindex=c->index;a.sll_protocol=htons(ETH_P_IP);a.sll_halen=6;memcpy(a.sll_addr,c->backend,6);
    if(sendto(fd,frame,(size_t)length,0,(struct sockaddr*)&a,sizeof(a))!=length){atomic_fetch_add(&s->errors,1);return -1;}
    atomic_fetch_add(&s->forwarded,1);return 1;
}
static int reap(pid_t pid) {
    int status;long long deadline=now_ms()+1000;
    while(now_ms()<deadline){pid_t r=waitpid(pid,&status,WNOHANG);if(r==pid)return 0;if(r<0&&errno!=EINTR)return -1;delay(20);}
    kill(pid,SIGKILL);while(waitpid(pid,&status,0)<0&&errno==EINTR){}return 0;
}
int main(int argc,char **argv) {
    if(argc!=2 || (strcmp(argv[1],"run")&&strcmp(argv[1],"recover")&&strcmp(argv[1],"preflight")&&strcmp(argv[1],"guardcheck")))return 64;
    if(!strcmp(argv[1],"preflight")) {
        struct context c;if(geteuid()!=0||context(&c))return 82;
        int a=arp_socket(&c),b=receiver(&c);
        if(a>=0)close(a);if(b>=0)close(b);
        if(a<0||b<0)return 83;
        printf("{\"scope_valid\":true,\"packet_filter_attached\":true,\"arp_frames_sent\":0,\"packets_read\":0}\n");return 0;
    }
    int dry_run=!strcmp(argv[1],"guardcheck");
    int dir=directory();if(dir<0)return 65;
    int lock=local_file(dir,"lock",O_RDWR|O_CREAT);if(lock<0||flock(lock,LOCK_EX|LOCK_NB))return 66;
    struct context c;
    int recovering=!strcmp(argv[1],"recover");
    int journal=local_file(dir,"intent",O_RDONLY);
    if(recovering) {
        if(journal<0)return 67;
        unsigned char data[sizeof(c)+1];ssize_t n=read(journal,data,sizeof(data));close(journal);
        if(n!=sizeof(c))return 68;memcpy(&c,data,sizeof(c));
        if(memcmp(c.magic,"CPRPAIR1",8)||!matching(&c))return 69;
    } else {
        if(journal>=0){close(journal);return 70;}
        if(context(&c))return 71;
        int out=local_file(dir,"intent",O_WRONLY|O_CREAT|O_EXCL);if(out<0)return 72;
        int good=write(out,&c,sizeof(c))==(ssize_t)sizeof(c)&&fsync(out)==0;close(out);
        if(!good||fsync(dir))return 73;
    }
    int control=arp_socket(&c);if(control<0)return 74;
    if(recovering){int result=restore_neighbor(control,&c);if(!result)unlinkat(dir,"intent",0);close(control);return result?75:0;}
    int data=receiver(&c);if(data<0){unlinkat(dir,"intent",0);return 76;}
    struct counters *s=mmap(NULL,sizeof(*s),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);if(s==MAP_FAILED){unlinkat(dir,"intent",0);return 77;}
    atomic_init(&s->forwarded,0);atomic_init(&s->fixture_tcp,0);atomic_init(&s->fixture_get,0);atomic_init(&s->errors,0);
    signal(SIGTERM,stopped);signal(SIGINT,stopped);signal(SIGHUP,SIG_IGN);signal(SIGPIPE,SIG_IGN);signal(SIGCHLD,SIG_DFL);
    int ready[2];if(pipe2(ready,O_CLOEXEC)){unlinkat(dir,"intent",0);return 78;}
    pid_t owner=getpid(),child=fork();if(child<0){close(ready[0]);close(ready[1]);unlinkat(dir,"intent",0);return 78;}
    if(child==0) {
        close(ready[0]);
        if(prctl(PR_SET_PDEATHSIG,SIGTERM)||getppid()!=owner){if(!dry_run)restore_neighbor(control,&c);_exit(79);}
        char value='1';if(write(ready[1],&value,1)!=1){if(!dry_run)restore_neighbor(control,&c);_exit(79);}close(ready[1]);
        long long deadline=now_ms()+12000;
        while(!stop_now&&getppid()==owner&&now_ms()<deadline) {
            if(dry_run){delay(50);continue;}
            struct pollfd p={data,POLLIN,0};int ready=poll(&p,1,50);
            if(ready>0&&relay_one(data,&c,s)<0)break;
        }
        int restored=dry_run?0:restore_neighbor(control,&c);_exit(restored?80:0);
    }
    close(ready[1]);struct pollfd readiness={ready[0],POLLIN,0};char value=0;
    int armed=poll(&readiness,1,2000)>0&&read(ready[0],&value,1)==1&&value=='1';close(ready[0]);
    if(!armed){kill(child,SIGTERM);reap(child);unlinkat(dir,"intent",0);return 84;}
    long long deadline=now_ms()+(dry_run?1000:8000),next=0;int fail=0;
    while(!stop_now&&now_ms()<deadline) {
        if(!dry_run&&now_ms()>=next){if(!matching(&c)||announce(control,&c,0)){fail=1;break;}next=now_ms()+1000;}
        int status;pid_t ended=waitpid(child,&status,WNOHANG);
        if(ended==child){child=0;fail=1;break;}
        if(ended<0&&errno!=EINTR){child=0;fail=1;break;}
        delay(20);
    }
    int restored=dry_run?0:restore_neighbor(control,&c);
    if(child>0){kill(child,SIGTERM);reap(child);}
    unsigned long before=atomic_load(&s->forwarded);
    long long observe=now_ms()+(dry_run?0:2500);
    while(now_ms()<observe) {
        struct pollfd p={data,POLLIN,0};if(poll(&p,1,50)>0 && relay_one(data,&c,s)>0)restore_neighbor(control,&c);
    }
    unsigned long late=atomic_load(&s->forwarded)-before;
    if(!restored&&!late)unlinkat(dir,"intent",0);
    fsync(dir);
    printf("{\"dry_run\":%s,\"active_limit_seconds\":%d,\"target\":\"%s\",\"fixture_server\":\"%s\",\"forwarded_frames\":%lu,\"fixture_tcp_frames\":%lu,\"fixture_get_requests\":%lu,\"send_errors\":%lu,\"restoration_frames_sent\":%s,\"post_restore_frames\":%lu,\"recovery_required\":%s,\"failed\":%s,\"gateway_modified\":false,\"host_network_configuration_writes\":0}\n",dry_run?"true":"false",dry_run?1:8,TARGET,SERVER,atomic_load(&s->forwarded),atomic_load(&s->fixture_tcp),atomic_load(&s->fixture_get),atomic_load(&s->errors),dry_run||restored?"false":"true",late,restored||late?"true":"false",fail?"true":"false");
    close(data);close(control);close(lock);close(dir);return restored||late||fail?81:0;
}
