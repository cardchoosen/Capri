/* Only CAPRI_GATEWAY_RELAY builds. Suppress kernel transit handling after AF_PACKET cloning.
   Exact selected source/interface, non-local destination; no blanket FORWARD/route/sysctl changes. */
static int gateway_valid(const char *name) {
    if(memcmp(name,"GWPT",4)||name[20])return 0;
    for(int i=4;i<20;i++)if(!((name[i]>='0'&&name[i]<='9')||(name[i]>='a'&&name[i]<='f')))return 0;
    return 1;
}
static int gateway_rule(const char *op,const char *name,int kind) {
    if(!gateway_valid(name))return -1;
    pid_t owner=getpid(),pid=fork();if(pid<0)return -1;
    if(!pid) {
        if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=owner)_exit(125);
        int sink=open("/dev/null",O_WRONLY);if(sink<0)_exit(125);dup2(sink,1);dup2(sink,2);close(sink);
        if(kind==1)execl("/system/bin/iptables","iptables","-w","2","-t","mangle",op,"PREROUTING","-i","wlan0","-s",TARGET"/32","-m","pkttype","--pkt-type","unicast","!","-d",SELF"/32","-m","comment","--comment",name,"-j",name,(char*)NULL);
        else if(kind==2)execl("/system/bin/iptables","iptables","-w","2","-t","mangle",op,name,"-m","comment","--comment",name,"-j","DROP",(char*)NULL);
        else execl("/system/bin/iptables","iptables","-w","2","-t","mangle",op,name,(char*)NULL);
        _exit(125);
    }
    int status;long long end=now_ms()+4000;
    while(now_ms()<end){pid_t r=waitpid(pid,&status,WNOHANG);if(r==pid)return WIFEXITED(status)?WEXITSTATUS(status):126;if(r<0&&errno!=EINTR)return -1;delay(10);}
    kill(pid,SIGKILL);while(waitpid(pid,&status,0)<0&&errno==EINTR){}return 124;
}
static int gateway_name(char name[25]) {
    unsigned char nonce[8];int fd=open("/dev/urandom",O_RDONLY|O_CLOEXEC);if(fd<0)return -1;
    ssize_t n=read(fd,nonce,8);close(fd);if(n!=8)return -1;
    memset(name,0,25);memcpy(name,"GWPT",4);for(int i=0;i<8;i++)snprintf(name+4+i*2,3,"%02x",nonce[i]);
    return gateway_rule("-S",name,0)==1?0:-1;
}
static int gateway_apply(const char *name) {
    return gateway_rule("-N",name,0)||gateway_rule("-A",name,2)||gateway_rule("-A",name,1)?-1:0;
}
static int gateway_cleanup(const char *name) {
    int r=gateway_rule("-S",name,0);if(r==1)return 0;if(r)return -1;
    r=gateway_rule("-C",name,1);if(r==0){if(gateway_rule("-D",name,1))return -1;}else if(r!=1)return -1;
    r=gateway_rule("-C",name,2);if(r==0){if(gateway_rule("-D",name,2))return -1;}else if(r!=1)return -1;
    if(gateway_rule("-X",name,0))return -1;
    return gateway_rule("-S",name,0)==1?0:-1;
}
