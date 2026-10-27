#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <linux/virtio_net.h>
#include <net/if.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static ino_t original_ns;
static long long now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (long long)t.tv_sec*1000+t.tv_nsec/1000000;}
static ino_t nsid(void){struct stat s;return stat("/proc/self/ns/net",&s)?0:s.st_ino;}
static int isolated(void){return nsid()&&nsid()!=original_ns&&!if_nametoindex("wlan0");}
static int wait_for(pid_t child,int ms){
    long long end=now()+ms;int status;
    while(now()<end){pid_t r=waitpid(child,&status,WNOHANG);if(r==child)return WIFEXITED(status)?WEXITSTATUS(status):126;if(r<0&&errno!=EINTR)return -1;usleep(10000);}
    kill(child,SIGKILL);while(waitpid(child,&status,0)<0&&errno==EINTR){}return 124;
}
static int ip(char *const argv[]){
    pid_t owner=getpid(),child=fork();if(child<0)return -1;
    if(!child){if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=owner||!isolated())_exit(125);execv(argv[0],argv);_exit(125);}
    return wait_for(child,2000);
}
#define IP(...) ip((char*const[]){"/system/bin/ip",__VA_ARGS__,NULL})
static int packet(const char *name,int vnet){
    int fd=socket(AF_PACKET,SOCK_RAW|SOCK_NONBLOCK|SOCK_CLOEXEC,htons(ETH_P_IP));if(fd<0)return -1;
    struct sockaddr_ll a={.sll_family=AF_PACKET,.sll_protocol=htons(ETH_P_IP),.sll_ifindex=(int)if_nametoindex(name)};
    int yes=1;
    if((vnet&&setsockopt(fd,SOL_PACKET,PACKET_VNET_HDR,&yes,sizeof(yes)))||bind(fd,(struct sockaddr*)&a,sizeof(a))){close(fd);return -1;}return fd;
}
static int mac(int fd,const char *name,unsigned char result[6]){struct ifreq r;memset(&r,0,sizeof(r));snprintf(r.ifr_name,sizeof(r.ifr_name),"%s",name);if(ioctl(fd,SIOCGIFHWADDR,&r))return -1;memcpy(result,r.ifr_hwaddr.sa_data,6);return 0;}
static void word(unsigned char *b,unsigned value){b[0]=(unsigned char)(value>>8);b[1]=(unsigned char)value;}
static unsigned checksum(const unsigned char *p,size_t n){unsigned sum=0;for(size_t i=0;i<n;i+=2)sum+=(unsigned)p[i]<<8|(i+1<n?p[i+1]:0);while(sum>>16)sum=(sum&65535)+(sum>>16);return (~sum)&65535;}
static int lab(void){
    if(unshare(CLONE_NEWNET)||!isolated())return 70;
    const char *stage="veth_create";int result=1,tx=-1,rx=-1;
    if(IP("link","add","gso_a","type","veth","peer","name","gso_b")||IP("link","set","gso_a","up")||IP("link","set","gso_b","up"))goto done;
    stage="sockets";tx=packet("gso_a",0);rx=packet("gso_b",1);if(tx<0||rx<0)goto done;
    unsigned char frame[2060]={0};if(mac(tx,"gso_a",frame+6)||mac(tx,"gso_b",frame))goto done;
    frame[12]=8;frame[13]=0;frame[14]=0x45;word(frame+16,sizeof(frame)-14);frame[22]=64;frame[23]=6;
    inet_pton(AF_INET,"192.0.2.1",frame+26);inet_pton(AF_INET,"192.0.2.2",frame+30);word(frame+24,checksum(frame+14,20));
    word(frame+34,20000);word(frame+36,20001);frame[46]=0x50;frame[47]=0x18;word(frame+48,32768);memset(frame+54,'x',sizeof(frame)-54);
    unsigned char pseudo[12];memcpy(pseudo,frame+26,8);pseudo[8]=0;pseudo[9]=6;word(pseudo+10,sizeof(frame)-34);word(frame+50,(~checksum(pseudo,sizeof(pseudo)))&65535);
    struct sockaddr_ll dest={.sll_family=AF_PACKET,.sll_protocol=htons(ETH_P_IP),.sll_ifindex=(int)if_nametoindex("gso_a"),.sll_halen=6};memcpy(dest.sll_addr,frame,6);
    stage="missing_metadata_rejected";errno=0;
    if(sendto(tx,frame,sizeof(frame),0,(struct sockaddr*)&dest,sizeof(dest))!=-1||errno!=EMSGSIZE)goto done;
    stage="vnet_send";int enabled=1;if(setsockopt(tx,SOL_PACKET,PACKET_VNET_HDR,&enabled,sizeof(enabled)))goto done;
    struct virtio_net_hdr header={.flags=VIRTIO_NET_HDR_F_NEEDS_CSUM,.gso_type=VIRTIO_NET_HDR_GSO_TCPV4,.hdr_len=54,.gso_size=1460,.csum_start=34,.csum_offset=16};
    struct iovec iov[]={{&header,sizeof(header)},{frame,sizeof(frame)}};
    struct msghdr delivery={.msg_name=&dest,.msg_namelen=sizeof(dest),.msg_iov=iov,.msg_iovlen=2};
    if(sendmsg(tx,&delivery,0)!=(ssize_t)(sizeof(header)+sizeof(frame)))goto done;
    stage="vnet_receive";struct pollfd p={rx,POLLIN,0};if(poll(&p,1,1500)<=0)goto done;
    unsigned char data[4096];ssize_t n=recv(rx,data,sizeof(data),0);
    if(n!=(ssize_t)(sizeof(header)+sizeof(frame)))goto done;
    struct virtio_net_hdr received;memcpy(&received,data,sizeof(received));
    if((received.gso_type&~VIRTIO_NET_HDR_GSO_ECN)!=VIRTIO_NET_HDR_GSO_TCPV4||received.gso_size!=1460||memcmp(data+sizeof(received),frame,sizeof(frame)))goto done;
    printf("{\"isolated\":true,\"physical_interface_absent\":true,\"frame_bytes\":2060,\"without_metadata_errno\":%d,\"vnet_round_trip\":true,\"gso_size\":1460,\"global_offloads_changed\":false}\n",EMSGSIZE);result=0;
done:
    if(result)fprintf(stderr,"vnet_lab_failed stage=%s errno=%d\n",stage,errno);
    if(tx>=0)close(tx);if(rx>=0)close(rx);
    if(isolated()&&if_nametoindex("gso_a")&&IP("link","del","gso_a"))return 72;
    return result;
}
int main(void){
    if(geteuid()!=0)return 64;original_ns=nsid();if(!original_ns)return 65;
    pid_t owner=getpid(),child=fork();if(child<0)return 66;
    if(!child){if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=owner)_exit(67);int result=lab();fflush(stdout);fflush(stderr);_exit(result);}
    int result=wait_for(child,12000);if(nsid()!=original_ns)return 68;return result;
}
