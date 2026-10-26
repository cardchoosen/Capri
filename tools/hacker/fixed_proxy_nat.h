/* Explicit CAPRI_HTTP_PROXY_TEST build only. Fixed fixture, no global flush/forward toggle. */
static int nat_valid(const char name[25]) {
    if(memcmp(name,"CPX_",4)||name[20])return 0;
    for(int i=4;i<20;i++)if(!((name[i]>='0'&&name[i]<='9')||(name[i]>='a'&&name[i]<='f')))return 0;
    return 1;
}
static int nat_command(const char *op,const char *chain,int rule) {
    if(!nat_valid(chain))return -1;
    pid_t owner=getpid(),pid=fork();if(pid<0)return -1;
    if(!pid) {
        if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=owner)_exit(125);
        int sink=open("/dev/null",O_WRONLY);if(sink<0)_exit(125);
        dup2(sink,1);dup2(sink,2);close(sink);
        if(rule==1)execl("/system/bin/iptables","iptables","-w","2","-t","nat",op,"PREROUTING","-i","wlan0","-s",TARGET"/32","-d",SERVER"/32","-p","tcp","--dport","18765","-m","comment","--comment",chain,"-j",chain,(char*)NULL);
        else if(rule==2)execl("/system/bin/iptables","iptables","-w","2","-t","nat",op,chain,"-p","tcp","-m","comment","--comment",chain,"-j","REDIRECT","--to-ports","18766",(char*)NULL);
        else execl("/system/bin/iptables","iptables","-w","2","-t","nat",op,chain,(char*)NULL);
        _exit(125);
    }
    int status;long long end=now_ms()+4000;
    while(now_ms()<end) {
        pid_t r=waitpid(pid,&status,WNOHANG);
        if(r==pid)return WIFEXITED(status)?WEXITSTATUS(status):126;
        if(r<0&&errno!=EINTR)return -1;
        delay(10);
    }
    kill(pid,SIGKILL);while(waitpid(pid,&status,0)<0&&errno==EINTR){}return 124;
}
static int nat_name(char name[25]) {
    unsigned char bytes[8];int fd=open("/dev/urandom",O_RDONLY|O_CLOEXEC);if(fd<0)return -1;
    ssize_t n=read(fd,bytes,8);close(fd);if(n!=8)return -1;
    memset(name,0,25);memcpy(name,"CPX_",4);
    for(int i=0;i<8;i++)snprintf(name+4+i*2,3,"%02x",bytes[i]);
    return nat_command("-S",name,0)==1?0:-1;
}
static int nat_apply(const char *name) {
    if(nat_command("-N",name,0)||nat_command("-A",name,2)||nat_command("-A",name,1))return -1;
    return 0;
}
static int nat_cleanup(const char *name) {
    if(!nat_valid(name))return -1;
    /* A vanished chain cannot still be a jump target. Check it before -C:
       iptables may return 2 for a missing jump target, not 1 for an absent rule. */
    int r=nat_command("-S",name,0);if(r==1)return 0;if(r)return -1;
    r=nat_command("-C",name,1);
    if(r==0){if(nat_command("-D",name,1))return -1;}
    else if(r!=1)return -1;
    r=nat_command("-S",name,0);if(r==1)return 0;if(r)return -1;
    r=nat_command("-C",name,2);
    if(r==0){if(nat_command("-D",name,2))return -1;}
    else if(r!=1)return -1;
    /* Extra rules/references prevent -X: preserve intent and fail, never flush. */
    if(nat_command("-X",name,0))return -1;
    return nat_command("-S",name,0)==1?0:-1;
}
static int proxy_ready(void) {
    int fd=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0);if(fd<0)return 0;
    struct sockaddr_in a={.sin_family=AF_INET,.sin_port=htons(18766)};inet_pton(AF_INET,SELF,&a.sin_addr);
    int ok=0;
    if(connect(fd,(struct sockaddr*)&a,sizeof(a))&&errno!=EINPROGRESS)goto done;
    struct pollfd p={fd,POLLOUT,0};int error=0;socklen_t size=sizeof(error);
    if(poll(&p,1,1000)<=0||getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&size)||error)goto done;
    const char request[]="GET /_capri_proxy_health HTTP/1.0\r\nHost: "SELF"\r\n\r\n";
    if(send(fd,request,sizeof(request)-1,MSG_NOSIGNAL)!=(ssize_t)sizeof(request)-1)goto done;
    char reply[1024];size_t used=0;long long end=now_ms()+1500;
    while(now_ms()<end&&used<sizeof(reply)-1) {
        p.events=POLLIN;if(poll(&p,1,100)<=0)continue;
        ssize_t n=recv(fd,reply+used,sizeof(reply)-1-used,0);
        if(n<=0)break;used+=(size_t)n;reply[used]=0;
        if(strstr(reply,"200 OK")&&strstr(reply,"{\"ready\":true}")){ok=1;break;}
    }
done:close(fd);return ok;
}
