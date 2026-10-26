#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <net/if.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* All mutations require a new network namespace without wlan0. No physical interface moves. */
static ino_t original_namespace;
static pid_t endpoints[2] = {0,0};
static long long milliseconds(void) {
    struct timespec value; clock_gettime(CLOCK_MONOTONIC,&value);
    return (long long)value.tv_sec*1000+value.tv_nsec/1000000;
}
static void nap(void) { struct timespec value={0,20000000}; nanosleep(&value,NULL); }
static ino_t namespace_id(void) {
    struct stat st; return stat("/proc/self/ns/net",&st)?0:st.st_ino;
}
static int isolated(void) {
    ino_t id=namespace_id(); return id && id != original_namespace && if_nametoindex("wlan0")==0;
}
static int reap(pid_t pid,int timeout) {
    long long start=milliseconds(); int status=0;
    for (;;) {
        pid_t result=waitpid(pid,&status,WNOHANG);
        if (result == pid) return WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status);
        if (result < 0 && errno != EINTR) return -1;
        if (milliseconds()-start > timeout) {
            kill(pid,SIGKILL);
            while (waitpid(pid,&status,0)<0 && errno==EINTR) {}
            return 124;
        }
        nap();
    }
}
static int execute(pid_t target,char *const argv[]) {
    pid_t owner=getpid(),pid=fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        if (prctl(PR_SET_PDEATHSIG,SIGKILL) || getppid()!=owner) _exit(125);
        if (target) {
            char path[80]; snprintf(path,sizeof(path),"/proc/%d/ns/net",target);
            int fd=open(path,O_RDONLY | O_CLOEXEC);
            if (fd<0 || setns(fd,CLONE_NEWNET)) _exit(125);
            close(fd);
        }
        if (!isolated()) _exit(125);
        int sink=open("/dev/null",O_WRONLY | O_CLOEXEC);
        if (sink<0) _exit(125);
        dup2(sink,STDOUT_FILENO); dup2(sink,STDERR_FILENO); close(sink);
        execv(argv[0],argv); _exit(125);
    }
    return reap(pid,4000);
}
#define IP(target,...) execute(target,(char*const[]){"/system/bin/ip",__VA_ARGS__,NULL})
static int ping_at(pid_t target,const char *address) {
    return execute(target,(char*const[]){"/system/bin/ping","-n","-c","1","-W","1",(char*)address,NULL});
}
static pid_t keeper(void) {
    int ready[2]; if (pipe2(ready,O_CLOEXEC)) return -1;
    pid_t owner=getpid(),pid=fork();
    if (pid<0) { close(ready[0]);close(ready[1]);return -1; }
    if (pid==0) {
        close(ready[0]);
        if (prctl(PR_SET_PDEATHSIG,SIGKILL) || getppid()!=owner || unshare(CLONE_NEWNET) || !isolated()) _exit(126);
        char value='1';
        if (write(ready[1],&value,1)!=1) _exit(126);
        close(ready[1]);
        for (;;) pause();
    }
    close(ready[1]);
    struct pollfd event={ready[0],POLLIN,0};
    char value=0; int good=poll(&event,1,2000)>0 && read(ready[0],&value,1)==1 && value=='1';
    close(ready[0]);
    if (!good) { kill(pid,SIGKILL); reap(pid,1000); return -1; }
    return pid;
}
static int number(const char *path) {
    FILE *f=fopen(path,"r"); int value=-1;
    if (f) { if (fscanf(f,"%d",&value)!=1) value=-1; fclose(f); }
    return value;
}
static int set_number(const char *path,int value) {
    if (!isolated()) return -1;
    int fd=open(path,O_WRONLY | O_CLOEXEC); if(fd<0)return -1;
    char text[40];int n=snprintf(text,sizeof(text),"%d\n",value);
    int result=write(fd,text,(size_t)n)==n?0:-1;close(fd);return result;
}
struct parameter { char path[192];int value; };
struct snapshot { struct parameter values[512];int count; };
static int snapshot(struct snapshot *result) {
    result->count=0;
    const char *groups[]={"all","default","lo","lab_a","lab_b"};
    for(unsigned i=0;i<sizeof(groups)/sizeof(groups[0]);i++) {
        char path[160];snprintf(path,sizeof(path),"/proc/sys/net/ipv4/conf/%s",groups[i]);
        DIR *dir=opendir(path);if(!dir)return -1;
        struct dirent *entry;
        while((entry=readdir(dir))) {
            if(entry->d_name[0]=='.')continue;
            if(result->count>=512) { closedir(dir);return -1; }
            struct parameter *p=&result->values[result->count];
            int length=snprintf(p->path,sizeof(p->path),"%s/%s",path,entry->d_name);
            if(length<0 || (size_t)length>=sizeof(p->path)) { closedir(dir);return -1; }
            p->value=number(p->path); if(p->value<0) { closedir(dir);return -1; }
            result->count++;
        }
        closedir(dir);
    }
    return 0;
}
static int differences(const struct snapshot *before) {
    int count=0;
    for(int i=0;i<before->count;i++) if(number(before->values[i].path)!=before->values[i].value)count++;
    return count;
}
static int restore(const struct snapshot *before,int forwarding) {
    if(set_number("/proc/sys/net/ipv4/ip_forward",forwarding))return -1;
    /* Restore after the forwarding write, which may reset related IPv4 settings. */
    for(int pass=0;pass<2;pass++) {
        for(int i=0;i<before->count;i++) {
            const struct parameter *p=&before->values[i];
            if(number(p->path)!=p->value && set_number(p->path,p->value))return -1;
        }
    }
    return number("/proc/sys/net/ipv4/ip_forward")==forwarding && differences(before)==0?0:-1;
}
static void shutdown_endpoints(void) {
    for(int i=0;i<2;i++) if(endpoints[i]>0) { kill(endpoints[i],SIGKILL);reap(endpoints[i],1000);endpoints[i]=0; }
}
static int lab(void) {
    const char *stage="unshare";
    int result=1;
    if(unshare(CLONE_NEWNET) || !isolated())goto done;
    endpoints[0]=keeper();endpoints[1]=keeper();
    if(endpoints[0]<1 || endpoints[1]<1)goto done;
    stage="virtual_topology";
    char pid_a[24],pid_b[24];snprintf(pid_a,sizeof(pid_a),"%d",endpoints[0]);snprintf(pid_b,sizeof(pid_b),"%d",endpoints[1]);
    if(IP(0,"link","add","lab_a","type","veth","peer","name","peer_a") ||
       IP(0,"link","add","lab_b","type","veth","peer","name","peer_b") ||
       IP(0,"link","set","peer_a","netns",pid_a) || IP(0,"link","set","peer_b","netns",pid_b) ||
       IP(0,"addr","add","192.0.2.1/30","dev","lab_a") || IP(0,"addr","add","198.51.100.1/30","dev","lab_b") ||
       IP(0,"link","set","lab_a","up") || IP(0,"link","set","lab_b","up") ||
       IP(endpoints[0],"link","set","lo","up") || IP(endpoints[1],"link","set","lo","up") ||
       IP(endpoints[0],"addr","add","192.0.2.2/30","dev","peer_a") ||
       IP(endpoints[1],"addr","add","198.51.100.2/30","dev","peer_b") ||
       IP(endpoints[0],"link","set","peer_a","up") || IP(endpoints[1],"link","set","peer_b","up") ||
       IP(endpoints[0],"route","add","default","via","192.0.2.1") ||
       IP(endpoints[1],"route","add","default","via","198.51.100.1"))goto done;
    stage="local_reachability";
    if(ping_at(endpoints[0],"192.0.2.1") || ping_at(endpoints[1],"198.51.100.1"))goto done;
    stage="forwarding_baseline";
    struct snapshot before;
    int forwarding=number("/proc/sys/net/ipv4/ip_forward");
    if(forwarding!=0 || snapshot(&before))goto done;
    if(ping_at(endpoints[0],"198.51.100.2")==0)goto done;
    stage="enable_isolated_forwarding";
    if(set_number("/proc/sys/net/ipv4/ip_forward",1))goto done;
    int changed=differences(&before);
    if(ping_at(endpoints[0],"198.51.100.2"))goto done;
    stage="restore_isolated_parameters";
    if(restore(&before,forwarding))goto done;
    if(ping_at(endpoints[0],"198.51.100.2")==0)goto done;
    stage="route_recovery";
    if(IP(endpoints[0],"route","del","default","via","192.0.2.1") ||
       IP(endpoints[1],"route","del","default","via","198.51.100.1") ||
       IP(endpoints[0],"addr","del","192.0.2.2/30","dev","peer_a") ||
       IP(endpoints[1],"addr","del","198.51.100.2/30","dev","peer_b") ||
       IP(0,"addr","del","192.0.2.1/30","dev","lab_a") ||
       IP(0,"addr","del","198.51.100.1/30","dev","lab_b") ||
       IP(0,"link","del","lab_a") || IP(0,"link","del","lab_b"))goto done;
    if(if_nametoindex("lab_a") || if_nametoindex("lab_b"))goto done;
    printf("{\"isolated\":true,\"virtual_route_forwarding\":true,\"disabled_before\":true,\"enabled_ping_passed\":true,\"disabled_after_restore\":true,\"ipv4_parameter_count\":%d,\"changed_on_enable\":%d,\"parameters_restored\":true,\"virtual_interfaces_removed\":true}\n",before.count,changed);
    result=0;
done:
    shutdown_endpoints();
    if(result)fprintf(stderr,"isolated_lab_failed stage=%s errno=%d\n",stage,errno);
    return result;
}
int main(int argc,char **argv) {
    (void)argv;
    if(argc!=1 || geteuid()!=0)return 64;
    original_namespace=namespace_id(); if(!original_namespace)return 65;
    signal(SIGCHLD,SIG_DFL);
    pid_t owner=getpid(),pid=fork();if(pid<0)return 66;
    if(pid==0) {
        if(prctl(PR_SET_PDEATHSIG,SIGKILL) || getppid()!=owner)_exit(67);
        int result=lab();fflush(stdout);fflush(stderr);_exit(result);
    }
    int result=reap(pid,25000);
    if(namespace_id()!=original_namespace)return 68;
    return result;
}
