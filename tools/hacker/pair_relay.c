#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#ifdef CAPRI_GATEWAY_RELAY
#include <linux/virtio_net.h>
#endif
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
#define SELF "192.168.1.6"
#define TARGET "192.168.1.8"
#ifdef CAPRI_GATEWAY_RELAY
#ifdef CAPRI_HTTP_PROXY_TEST
#error Gateway passthrough must not use the fixed-server proxy build
#endif
#define SERVER "192.168.1.1"
#else
#define SERVER "192.168.1.3"
#endif
#if defined(CAPRI_HTTP_PROXY_TEST) || defined(CAPRI_GATEWAY_RELAY)
#define CAPRI_PACKET_TIMESTAMPS
#endif
#ifdef CAPRI_ORIGINAL_DST_PROBE
#ifndef CAPRI_HTTP_PROXY_TEST
#error Original-destination probe requires the NAT build
#endif
#define INTENT_MAGIC "CPROD001"
#elif defined(CAPRI_GATEWAY_RELAY)
#define INTENT_MAGIC "CPRGW001"
#else
#define INTENT_MAGIC "CPRPAIR1"
#endif
struct context { unsigned char magic[8];int index;uint32_t self,target,server;unsigned char own[6],client[6],backend[6]; };
struct counters { atomic_ulong forwarded,fixture_tcp,fixture_get,errors,proxy_admitted;
    atomic_int send_errno;atomic_ulong send_length;atomic_uint send_protocol;
    atomic_ulong gso_frames,checksum_frames;atomic_uint send_gso_type,send_vnet_flags;
#ifdef CAPRI_HTTP_PROXY_TEST
    atomic_uchar proxy_ports[8192]; /* Fixed peer pair + destination port: client source-port bitmap. */
#endif
};
static struct counters *current_counters;
#ifdef CAPRI_PACKET_TIMESTAMPS
static long long last_packet_ns;
static long long realtime_ns(void) { struct timespec t;clock_gettime(CLOCK_REALTIME,&t);return (long long)t.tv_sec*1000000000+t.tv_nsec; }
#endif
static volatile sig_atomic_t stop_now;
static void stopped(int value) { (void)value;stop_now=1; }
static long long now_ms(void) { struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (long long)t.tv_sec*1000+t.tv_nsec/1000000; }
static void delay(int ms) { struct timespec t={ms/1000,(ms%1000)*1000000};nanosleep(&t,NULL); }
#ifdef CAPRI_HTTP_PROXY_TEST
#include "fixed_proxy_nat.h"
#endif
#ifdef CAPRI_GATEWAY_RELAY
#include "gateway_resource.h"
#endif
static int directory(int create) {
    if(geteuid()!=0)return -1;
    if(create && mkdir(DIR,0700) && errno!=EEXIST)return -1;
    int fd=open(DIR,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);struct stat s;
    if(fd<0)return -1;
    if(fstat(fd,&s)||s.st_uid!=0||(s.st_mode&0777)!=0700){close(fd);errno=EPERM;return -1;}
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
#ifdef CAPRI_GATEWAY_RELAY
static int only_known_local_addresses(int fd,uint32_t own) {
    struct ifreq entries[64];struct ifconf list={.ifc_len=sizeof(entries),.ifc_req=entries};
    if(ioctl(fd,SIOCGIFCONF,&list)||list.ifc_len>=(int)sizeof(entries))return 0;
    for(int i=0;i<list.ifc_len/(int)sizeof(struct ifreq);i++) {
        struct sockaddr_in *a=(struct sockaddr_in*)&entries[i].ifr_addr;
        if(a->sin_family==AF_INET&&a->sin_addr.s_addr!=own&&(ntohl(a->sin_addr.s_addr)>>24)!=127)return 0;
    }
    return 1;
}
#endif
static int context(struct context *c) {
    memset(c,0,sizeof(*c));memcpy(c->magic,INTENT_MAGIC,8);
    inet_pton(AF_INET,SELF,&c->self);inet_pton(AF_INET,TARGET,&c->target);inet_pton(AF_INET,SERVER,&c->server);
    c->index=(int)if_nametoindex("wlan0");if(c->index<1)return -1;
    int fd=socket(AF_INET,SOCK_DGRAM|SOCK_CLOEXEC,0);if(fd<0)return -1;
    struct ifreq r;memset(&r,0,sizeof(r));strcpy(r.ifr_name,"wlan0");
    int result=-1;
#ifdef CAPRI_GATEWAY_RELAY
    if(!only_known_local_addresses(fd,c->self))goto done;
#endif
    if(ioctl(fd,SIOCGIFADDR,&r)||((struct sockaddr_in*)&r.ifr_addr)->sin_addr.s_addr!=c->self)goto done;
    if(ioctl(fd,SIOCGIFHWADDR,&r))goto done;
    memcpy(c->own,r.ifr_hwaddr.sa_data,6);
    if(neighbor(fd,c->target,c->client)||neighbor(fd,c->server,c->backend))goto done;
    if(!memcmp(c->own,c->client,6)||!memcmp(c->own,c->backend,6)||!memcmp(c->client,c->backend,6))goto done;
    result=0;
done:close(fd);return result;
}
static int matching(const struct context *c) {
    struct context current;
    return !context(&current)&&c->index==current.index&&c->self==current.self&&c->target==current.target&&c->server==current.server&&
        !memcmp(c->magic,current.magic,8)&&!memcmp(c->own,current.own,6)&&!memcmp(c->client,current.client,6)&&!memcmp(c->backend,current.backend,6);
}
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
#ifdef CAPRI_GATEWAY_RELAY
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,30),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,ntohl(c->self),0,1),BPF_STMT(BPF_RET|BPF_K,0),
        BPF_STMT(BPF_ALU|BPF_AND|BPF_K,0xff000000),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0x7f000000,0,1),BPF_STMT(BPF_RET|BPF_K,0),
#else
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,30),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,ntohl(c->server),1,0),BPF_STMT(BPF_RET|BPF_K,0),
#endif
        BPF_STMT(BPF_RET|BPF_K,65550)
    };
#ifdef CAPRI_PACKET_TIMESTAMPS
    int enabled=1;
    if(setsockopt(fd,SOL_SOCKET,SO_TIMESTAMPNS,&enabled,sizeof(enabled))){close(fd);return -1;}
#endif
#ifdef CAPRI_GATEWAY_RELAY
    int use_vnet=1;
    if(setsockopt(fd,SOL_PACKET,PACKET_VNET_HDR,&use_vnet,sizeof(use_vnet))){close(fd);return -1;}
#endif
    struct sock_fprog p={(unsigned short)(sizeof(f)/sizeof(f[0])),f};
    struct sockaddr_ll a;memset(&a,0,sizeof(a));a.sll_family=AF_PACKET;a.sll_ifindex=c->index;a.sll_protocol=htons(ETH_P_IP);
    if(setsockopt(fd,SOL_SOCKET,SO_ATTACH_FILTER,&p,sizeof(p))||bind(fd,(struct sockaddr*)&a,sizeof(a))){close(fd);return -1;}
    return fd;
}
static int relay_one(int fd,const struct context *c,struct counters *s) {
    unsigned char frame[65550];struct sockaddr_ll from;socklen_t size=sizeof(from);
#ifdef CAPRI_PACKET_TIMESTAMPS
#ifdef CAPRI_GATEWAY_RELAY
    struct virtio_net_hdr offload={0};
    struct iovec iov[]={{&offload,sizeof(offload)},{frame,sizeof(frame)}};
#else
    struct iovec iov[]={{frame,sizeof(frame)}};
#endif
    union { struct cmsghdr align;unsigned char data[CMSG_SPACE(sizeof(struct timespec))]; } ancillary;
    struct msghdr msg={.msg_name=&from,.msg_namelen=size,.msg_iov=iov,.msg_iovlen=sizeof(iov)/sizeof(iov[0]),.msg_control=ancillary.data,.msg_controllen=sizeof(ancillary.data)};
    ssize_t length=recvmsg(fd,&msg,0);last_packet_ns=0;
#ifdef CAPRI_GATEWAY_RELAY
    if(length>=0){
        if(length<(ssize_t)sizeof(offload)||(msg.msg_flags&MSG_TRUNC)){errno=EPROTO;return -1;}
        length-=(ssize_t)sizeof(offload);
    }
#endif
    for(struct cmsghdr *h=CMSG_FIRSTHDR(&msg);length>=0&&h;h=CMSG_NXTHDR(&msg,h)) {
        if(h->cmsg_level==SOL_SOCKET&&h->cmsg_type==SCM_TIMESTAMPNS&&h->cmsg_len>=CMSG_LEN(sizeof(struct timespec))) {
            struct timespec t;memcpy(&t,CMSG_DATA(h),sizeof(t));last_packet_ns=(long long)t.tv_sec*1000000000+t.tv_nsec;
        }
    }
#else
    ssize_t length=recvfrom(fd,frame,sizeof(frame),0,(struct sockaddr*)&from,&size);
#endif
    if(length<0)return errno==EAGAIN||errno==EINTR?0:-1;
    if(from.sll_pkttype==PACKET_OUTGOING||length<34||memcmp(frame,c->own,6)||memcmp(frame+6,c->client,6)||memcmp(frame+26,&c->target,4))return 0;
#ifdef CAPRI_GATEWAY_RELAY
    if(!memcmp(frame+30,&c->self,4)||(word(frame+30)>>24)==127)return 0;
#else
    if(memcmp(frame+30,&c->server,4))return 0;
#endif
    unsigned header=(frame[14]&15u)*4u;
    if(frame[23]==6 && header>=20 && length>=(ssize_t)(14+header+20) && !(frame[20]&0x3f) && !frame[21]) {
        unsigned char *tcp=frame+14+header;
#ifdef CAPRI_GATEWAY_RELAY
        if(half(tcp+2)==80&&word(frame+30)==0x7cdc33cbu) { /* Approved external server 124.220.51.203 */
#else
        if(half(tcp+2)==18765) {
#endif
            atomic_fetch_add(&s->fixture_tcp,1);
#ifndef CAPRI_GATEWAY_RELAY
            unsigned data=(tcp[12]>>4)*4u;
            if(data>=20 && length>=(ssize_t)(14+header+data+4) && !memcmp(tcp+data,"GET ",4))atomic_fetch_add(&s->fixture_get,1);
#endif
#ifdef CAPRI_HTTP_PROXY_TEST
            unsigned port=half(tcp),slot=port>>3,bit=1u<<(port&7);
            if((tcp[13]&0x16)==0x02)atomic_fetch_or(&s->proxy_ports[slot],(unsigned char)bit);
            /* Only flows whose opening SYN was observed belong to this proxy run.
               Existing direct TCP must retain its original peer and raw forwarding path. */
            if(atomic_load(&s->proxy_ports[slot])&bit){atomic_fetch_add(&s->proxy_admitted,1);return 1;}
#endif
        }
    }
    memcpy(frame,c->backend,6);memcpy(frame+6,c->own,6);
    struct sockaddr_ll a;memset(&a,0,sizeof(a));a.sll_family=AF_PACKET;a.sll_ifindex=c->index;a.sll_protocol=htons(ETH_P_IP);a.sll_halen=6;memcpy(a.sll_addr,c->backend,6);
#ifdef CAPRI_GATEWAY_RELAY
    struct iovec outgoing[]={{&offload,sizeof(offload)},{frame,(size_t)length}};
    struct msghdr delivery={.msg_name=&a,.msg_namelen=sizeof(a),.msg_iov=outgoing,.msg_iovlen=2};
    ssize_t expected=length+(ssize_t)sizeof(offload),sent=sendmsg(fd,&delivery,0);
#else
    ssize_t expected=length,sent=sendto(fd,frame,(size_t)length,0,(struct sockaddr*)&a,sizeof(a));
#endif
    if(sent!=expected){int failure=sent<0?errno:EIO;
#ifdef CAPRI_GATEWAY_RELAY
        atomic_store(&s->send_gso_type,offload.gso_type);atomic_store(&s->send_vnet_flags,offload.flags);
#endif
        atomic_store(&s->send_errno,failure);atomic_store(&s->send_length,(unsigned long)length);atomic_store(&s->send_protocol,frame[23]);atomic_fetch_add(&s->errors,1);return -1;}
#ifdef CAPRI_GATEWAY_RELAY
    if(offload.gso_type!=VIRTIO_NET_HDR_GSO_NONE)atomic_fetch_add(&s->gso_frames,1);
    if(offload.flags&VIRTIO_NET_HDR_F_NEEDS_CSUM)atomic_fetch_add(&s->checksum_frames,1);
#endif
    atomic_fetch_add(&s->forwarded,1);return 1;
}
#ifdef CAPRI_HTTP_PROXY_TEST
static void drain_proxy(int data,const struct context *c,struct counters *s) {
    long long end=now_ms()+800;
    while(now_ms()<end&&proxy_ready()&&!proxy_idle()) {
        struct pollfd p={data,POLLIN,0};
        if(poll(&p,1,30)>0)relay_one(data,c,s);
    }
}
#endif
static int reap(pid_t pid) {
    int status;long long deadline=now_ms()+1000;
    while(now_ms()<deadline){pid_t r=waitpid(pid,&status,WNOHANG);if(r==pid)return 0;if(r<0&&errno!=EINTR)return -1;delay(20);}
    kill(pid,SIGKILL);while(waitpid(pid,&status,0)<0&&errno==EINTR){}return 0;
}
struct intent { struct context c;uint32_t dry_run;
#ifdef CAPRI_HTTP_PROXY_TEST
    char nat_chain[25];
#endif
#ifdef CAPRI_GATEWAY_RELAY
    char gateway_chain[25];
#endif
};
static int present(int dir,const char *name) { struct stat s;return fstatat(dir,name,&s,AT_SYMLINK_NOFOLLOW)==0; }
static int phase(int dir,const char *name,int dry,int restored) {
    unlinkat(dir,"status.tmp",0);
    int fd=local_file(dir,"status.tmp",O_WRONLY|O_CREAT|O_EXCL);if(fd<0)return -1;
    char metrics[256]="null";
    if(current_counters)snprintf(metrics,sizeof(metrics),"{\"forwarded_frames\":%lu,\"fixture_get_requests\":%lu,\"send_errors\":%lu,\"proxy_admitted_frames\":%lu}",atomic_load(&current_counters->forwarded),atomic_load(&current_counters->fixture_get),atomic_load(&current_counters->errors),atomic_load(&current_counters->proxy_admitted));
    char json[640];int n=snprintf(json,sizeof(json),"{\"phase\":\"%s\",\"dry_run\":%s,\"restoration_frames_sent\":%s,\"metrics\":%s}",name,dry?"true":"false",restored?"true":"false",metrics);
    int good=write(fd,json,(size_t)n)==n&&fsync(fd)==0;close(fd);
    return !good||renameat(dir,"status.tmp",dir,"status.json")||fsync(dir)?-1:0;
}
static int status_or_stop(int dir,int stop) {
    int lock=local_file(dir,"lock",O_RDWR|O_CREAT);if(lock<0)return 85;
    int active=flock(lock,LOCK_EX|LOCK_NB)!=0;
    if(active && errno!=EWOULDBLOCK && errno!=EAGAIN){close(lock);return 85;}
    if(active && stop) {
        int fd=local_file(dir,"stop.request",O_WRONLY|O_CREAT|O_EXCL);
        if(fd<0 && errno!=EEXIST){close(lock);return 86;}
        if(fd>=0){fsync(fd);close(fd);fsync(dir);}
    }
    char value[640]="null";int fd=local_file(dir,"status.json",O_RDONLY);
    if(fd>=0){ssize_t n=read(fd,value,sizeof(value)-1);close(fd);if(n>0)value[n]=0;}
    int pending=present(dir,"intent");
    printf("{\"active\":%s,\"intent_present\":%s,\"recovery_required\":%s,\"stop_requested\":%s,\"state\":%s}\n",active?"true":"false",pending?"true":"false",!active&&pending?"true":"false",active&&stop?"true":"false",value);
    close(lock);return 0;
}
int main(int argc,char **argv) {
    if(argc!=2)return 64;
    int start=!strcmp(argv[1],"run")||!strcmp(argv[1],"start");
    int long_guard=!strcmp(argv[1],"guardstart");
    int dry_run=!strcmp(argv[1],"guardcheck")||long_guard;
    int fault=0;
#ifdef CAPRI_RELAY_FAULT_TESTS
    if(!strcmp(argv[1],"fault_worker")){start=1;fault=1;}
    if(!strcmp(argv[1],"fault_controller")){start=1;fault=2;}
    if(!strcmp(argv[1],"guard_fault_worker")){dry_run=1;fault=1;}
    if(!strcmp(argv[1],"guard_fault_controller")){dry_run=1;fault=2;}
#endif
#ifdef CAPRI_ORIGINAL_DST_PROBE
    if(start)return 64; /* Also reject active fault actions if a developer combines build flags. */
#endif
    int recovering=!strcmp(argv[1],"recover");
    int querying=!strcmp(argv[1],"status"),stopping=!strcmp(argv[1],"stop");
    if(!strcmp(argv[1],"preflight")) {
        struct context c;if(geteuid()!=0||context(&c))return 82;
        int a=arp_socket(&c),b=receiver(&c);if(a>=0)close(a);if(b>=0)close(b);
        if(a<0||b<0)return 83;
        printf("{\"scope_valid\":true,\"packet_filter_attached\":true,\"arp_frames_sent\":0,\"packets_read\":0}\n");return 0;
    }
    if(!start&&!dry_run&&!recovering&&!querying&&!stopping)return 64;
    int dir=directory(start||dry_run);
    if(dir<0){
        if(geteuid()==0&&errno==ENOENT&&(querying||stopping||recovering)){
            printf("{\"active\":false,\"intent_present\":false,\"recovery_required\":false,\"state\":null}\n");return 0;
        }
        return 65;
    }
    if(querying||stopping)return status_or_stop(dir,stopping);
    int lock=local_file(dir,"lock",O_RDWR|O_CREAT);if(lock<0||flock(lock,LOCK_EX|LOCK_NB))return 66;
    struct context c;struct intent saved;memset(&saved,0,sizeof(saved));
    int journal=local_file(dir,"intent",O_RDONLY);
    if(recovering) {
        if(journal<0){if(present(dir,"intent"))return 68;unlinkat(dir,"stop.request",0);close(lock);return status_or_stop(dir,0);}
        unsigned char data[sizeof(saved)+1];ssize_t n=read(journal,data,sizeof(data));close(journal);
        if(n!=sizeof(saved))return 68;memcpy(&saved,data,sizeof(saved));c=saved.c;dry_run=(int)saved.dry_run;
        if(saved.dry_run>1||memcmp(c.magic,INTENT_MAGIC,8))return 69;
#if !defined(CAPRI_HTTP_PROXY_TEST) && !defined(CAPRI_GATEWAY_RELAY)
        if(!dry_run&&!matching(&c))return 69;
#endif
        int result=0;
#ifdef CAPRI_HTTP_PROXY_TEST
        if(!nat_valid(saved.nat_chain))return 69;
#endif
#ifdef CAPRI_GATEWAY_RELAY
        if(!gateway_valid(saved.gateway_chain))return 69;
#endif
        if(!dry_run){
            if(!matching(&c))result=-1;
            else {int fd=arp_socket(&c);if(fd<0)result=-1;else {result=restore_neighbor(fd,&c);close(fd);}}
        }
#ifdef CAPRI_HTTP_PROXY_TEST
        if(nat_cleanup(saved.nat_chain))result=-1;
#endif
#ifdef CAPRI_GATEWAY_RELAY
        if(gateway_cleanup(saved.gateway_chain))result=-1;
#endif
        if(!result){if(unlinkat(dir,"intent",0))return 75;unlinkat(dir,"stop.request",0);phase(dir,"recovered",dry_run,!dry_run);fsync(dir);}
        if(result)return 75;close(lock);return status_or_stop(dir,0);
    }
    if(journal>=0){close(journal);return 70;}
    if(present(dir,"stop.request"))return 87;
    if(context(&c))return 71;
    saved.c=c;saved.dry_run=(uint32_t)dry_run;
#ifdef CAPRI_HTTP_PROXY_TEST
    if(!proxy_ready()||nat_name(saved.nat_chain))return 89;
#endif
#ifdef CAPRI_GATEWAY_RELAY
    if(gateway_name(saved.gateway_chain))return 89;
#endif
    int out=local_file(dir,"intent",O_WRONLY|O_CREAT|O_EXCL);if(out<0)return 72;
    int good=write(out,&saved,sizeof(saved))==(ssize_t)sizeof(saved)&&fsync(out)==0;close(out);
    if(!good||fsync(dir)||phase(dir,"starting",dry_run,0))return 73;
    int control=arp_socket(&c);if(control<0)return 74;
    int data=receiver(&c);if(data<0){unlinkat(dir,"intent",0);return 76;}
    struct counters *s=mmap(NULL,sizeof(*s),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);if(s==MAP_FAILED){unlinkat(dir,"intent",0);return 77;}
    atomic_init(&s->forwarded,0);atomic_init(&s->fixture_tcp,0);atomic_init(&s->fixture_get,0);atomic_init(&s->errors,0);atomic_init(&s->proxy_admitted,0);atomic_init(&s->send_errno,0);atomic_init(&s->send_length,0);atomic_init(&s->send_protocol,0);atomic_init(&s->gso_frames,0);atomic_init(&s->checksum_frames,0);atomic_init(&s->send_gso_type,0);atomic_init(&s->send_vnet_flags,0);current_counters=s;
#ifdef CAPRI_HTTP_PROXY_TEST
    for(unsigned i=0;i<8192;i++)atomic_init(&s->proxy_ports[i],0);
#endif
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
#ifdef CAPRI_HTTP_PROXY_TEST
        if(!dry_run)drain_proxy(data,&c,s);
#endif
        int restored=dry_run?0:restore_neighbor(control,&c);
#ifdef CAPRI_HTTP_PROXY_TEST
        if(getppid()!=owner&&nat_cleanup(saved.nat_chain))restored=-1;
#endif
#ifdef CAPRI_GATEWAY_RELAY
        if(getppid()!=owner&&gateway_cleanup(saved.gateway_chain))restored=-1;
#endif
        _exit(restored?80:0);
    }
    close(ready[1]);struct pollfd readiness={ready[0],POLLIN,0};char value=0;
    int armed=poll(&readiness,1,2000)>0&&read(ready[0],&value,1)==1&&value=='1';close(ready[0]);
    if(!armed){kill(child,SIGTERM);reap(child);unlinkat(dir,"intent",0);phase(dir,"failed_before_start",dry_run,0);return 84;}
#ifdef CAPRI_HTTP_PROXY_TEST
    if(nat_apply(saved.nat_chain)) {
        kill(child,SIGTERM);reap(child);
        if(!nat_cleanup(saved.nat_chain))unlinkat(dir,"intent",0);
        phase(dir,"nat_setup_failed",dry_run,0);return 90;
    }
#endif
#ifdef CAPRI_GATEWAY_RELAY
    if(gateway_apply(saved.gateway_chain)) {
        kill(child,SIGTERM);reap(child);
        if(!gateway_cleanup(saved.gateway_chain))unlinkat(dir,"intent",0);
        phase(dir,"gateway_setup_failed",dry_run,0);return 90;
    }
#endif
    if(phase(dir,"running",dry_run,0)){kill(child,SIGTERM);reap(child);
#ifdef CAPRI_HTTP_PROXY_TEST
        if(!nat_cleanup(saved.nat_chain))unlinkat(dir,"intent",0);
#elif defined(CAPRI_GATEWAY_RELAY)
        if(!gateway_cleanup(saved.gateway_chain))unlinkat(dir,"intent",0);
#else
        unlinkat(dir,"intent",0);
#endif
        return 88;}
    long long started=now_ms(),deadline=started+(dry_run&&!fault&&!long_guard?1000:8000),next=0;int fail=0;
    const char *reason="deadline";int injected=0;
    while(!stop_now&&now_ms()<deadline) {
        if(present(dir,"stop.request")){reason="stop_requested";break;}
#ifdef CAPRI_HTTP_PROXY_TEST
        if(!dry_run&&now_ms()>=next&&!proxy_ready()){fail=1;reason="proxy_unavailable";break;}
#endif
        if(!dry_run&&now_ms()>=next){if(!matching(&c)||announce(control,&c,0)){fail=1;reason="context_or_send_failure";break;}next=now_ms()+1000;}
        if(fault&&!injected&&now_ms()-started>=(dry_run?250:2000)) {
            injected=1;
            if(fault==1)kill(child,SIGKILL);
            else {phase(dir,"controller_exit_injected",dry_run,0);_exit(91);}
        }
        int status;pid_t ended=waitpid(child,&status,WNOHANG);
        if(ended==child){child=0;fail=1;reason="worker_exit";break;}
        if(ended<0&&errno!=EINTR){child=0;fail=1;reason="worker_wait_error";break;}
        delay(20);
    }
    if(stop_now)reason="signal";
    phase(dir,"stopping",dry_run,0);
#ifdef CAPRI_HTTP_PROXY_TEST
    if(!dry_run)drain_proxy(data,&c,s);
#endif
    int restored=dry_run?0:restore_neighbor(control,&c);
    if(child>0){kill(child,SIGTERM);reap(child);}
#ifdef CAPRI_PACKET_TIMESTAMPS
    long long restored_at=realtime_ns();unsigned long late=0,queued=0;
#else
    unsigned long before=atomic_load(&s->forwarded)+atomic_load(&s->proxy_admitted);
#endif
    long long observe=now_ms()+(dry_run?0:2500);
    while(now_ms()<observe) {
        struct pollfd p={data,POLLIN,0};if(poll(&p,1,50)>0 && relay_one(data,&c,s)>0) {
#ifdef CAPRI_PACKET_TIMESTAMPS
            if(last_packet_ns>0&&last_packet_ns<=restored_at)queued++;
            else {late++;restore_neighbor(control,&c);}
#else
            restore_neighbor(control,&c);
#endif
        }
    }
#ifndef CAPRI_PACKET_TIMESTAMPS
    unsigned long late=atomic_load(&s->forwarded)+atomic_load(&s->proxy_admitted)-before;
#endif
#ifdef CAPRI_HTTP_PROXY_TEST
    /* Stop announcements and finish neighbor restoration while engine and NAT still live. */
    if(nat_cleanup(saved.nat_chain))restored=-1;
    printf("{\"proxy_admitted_frames\":%lu,\"pre_restore_queued_frames\":%lu,\"nat_cleanup_succeeded\":%s}\n",atomic_load(&s->proxy_admitted),queued,restored?"false":"true");
#endif
#ifdef CAPRI_GATEWAY_RELAY
    if(gateway_cleanup(saved.gateway_chain))restored=-1;
    printf("{\"send_errno\":%d,\"send_frame_length\":%lu,\"send_ip_protocol\":%u,\"vnet_gso_frames\":%lu,\"vnet_checksum_frames\":%lu,\"failed_gso_type\":%u,\"failed_vnet_flags\":%u}\n",atomic_load(&s->send_errno),atomic_load(&s->send_length),atomic_load(&s->send_protocol),atomic_load(&s->gso_frames),atomic_load(&s->checksum_frames),atomic_load(&s->send_gso_type),atomic_load(&s->send_vnet_flags));
    printf("{\"gateway_direction\":true,\"target_gateway_mapping_changed\":%s,\"router_modified\":false,\"kernel_transit_suppression_cleaned\":%s,\"pre_restore_queued_frames\":%lu,\"selected_external_tcp_frames\":%lu}\n",dry_run?"false":"true",restored?"false":"true",queued,atomic_load(&s->fixture_tcp));
#endif
    if(!restored&&!late){if(unlinkat(dir,"intent",0))restored=-1;}
    unlinkat(dir,"stop.request",0);
    phase(dir,restored||late?"needs_recovery":"stopped",dry_run,!dry_run&&!restored);
    fsync(dir);
    printf("{\"dry_run\":%s,\"reason\":\"%s\",\"fault_injected\":%s,\"active_limit_seconds\":%d,\"target\":\"%s\",\"fixture_server\":\"%s\",\"forwarded_frames\":%lu,\"fixture_tcp_frames\":%lu,\"fixture_get_requests\":%lu,\"send_errors\":%lu,\"restoration_frames_sent\":%s,\"post_restore_frames\":%lu,\"recovery_required\":%s,\"failed\":%s,\"gateway_modified\":false,\"host_network_configuration_modified\":%s}\n",dry_run?"true":"false",reason,injected?"true":"false",dry_run&&!fault&&!long_guard?1:8,TARGET,SERVER,atomic_load(&s->forwarded),atomic_load(&s->fixture_tcp),atomic_load(&s->fixture_get),atomic_load(&s->errors),dry_run||restored?"false":"true",late,restored||late?"true":"false",fail?"true":"false",
#if defined(CAPRI_HTTP_PROXY_TEST) || defined(CAPRI_GATEWAY_RELAY)
    "true"
#else
    "false"
#endif
    );
    close(data);close(control);close(lock);close(dir);return restored||late||fail?81:0;
}
