/* Included only by the explicitly built, unreferenced-chain probe. No hooks into traffic. */
#define CHAIN_INTENT "chain.intent"
static int firewall(const char *operation, const char *chain, int owner_rule) {
    pid_t owner=getpid();
    pid_t pid=fork();
    if (pid < 0) return -1;
    if (pid == 0) {
#ifdef __linux__
        if (prctl(PR_SET_PDEATHSIG,SIGKILL)) _exit(125);
#endif
        if (getppid() != owner) _exit(125);
        int sink=open("/dev/null",O_WRONLY);
        if (sink < 0) _exit(125);
        dup2(sink,STDOUT_FILENO); dup2(sink,STDERR_FILENO); close(sink);
        char tag[80]; snprintf(tag,sizeof(tag),"capri-lifecycle:%s",chain);
        if (owner_rule) execl("/system/bin/iptables","iptables","-w","2","-t","filter",operation,chain,"-m","comment","--comment",tag,"-j","RETURN",(char*)NULL);
        else execl("/system/bin/iptables","iptables","-w","2","-t","filter",operation,chain,(char*)NULL);
        _exit(125);
    }
    long long start=clock_ms(); int status=0;
    for (;;) {
        pid_t ended=waitpid(pid,&status,WNOHANG);
        if (ended == pid) return WIFEXITED(status)?WEXITSTATUS(status):126;
        if (ended < 0 && errno != EINTR) return -1;
        if (clock_ms()-start > 4000) {
            kill(pid,SIGKILL);
            while (waitpid(pid,&status,0)<0 && errno==EINTR) {}
            return 124;
        }
        pause_ms();
    }
}
static int read_chain(int dir,char chain[32]) {
    int fd=safe_file(dir,CHAIN_INTENT,O_RDONLY);
    if (fd < 0) return -1;
    char raw[33]; ssize_t size=read(fd,raw,sizeof(raw)); close(fd);
    if (size != 25 || memcmp(raw,"CPRI_LAB_",9)) return -1;
    for (int i=9;i<25;i++) if (!((raw[i]>='0'&&raw[i]<='9')||(raw[i]>='a'&&raw[i]<='f'))) return -1;
    memcpy(chain,raw,25); chain[25]=0; return 0;
}
static int resource_apply(int dir) {
    unsigned char nonce[8];
    int random=open("/dev/urandom",O_RDONLY | O_CLOEXEC);
    if (random < 0) return -1;
    ssize_t size=read(random,nonce,sizeof(nonce)); close(random);
    if (size != sizeof(nonce)) return -1;
    char chain[32]="CPRI_LAB_";
    for (int i=0;i<8;i++) snprintf(chain+9+2*i,3,"%02x",nonce[i]);
    /* Refuse any pre-existing chain; never repurpose or flush one. */
    if (firewall("-S",chain,0) != 1) return -1;
    int fd=safe_file(dir,"chain.intent.tmp",O_WRONLY | O_CREAT | O_EXCL);
    if (fd < 0) return -1;
    int good=write(fd,chain,25)==25 && fsync(fd)==0;
    close(fd);
    if (!good || renameat(dir,"chain.intent.tmp",dir,CHAIN_INTENT) || fsync(dir)) return -1;
    /* Journal is durable before the first kernel write. No INPUT/OUTPUT/FORWARD jumps. */
    if (firewall("-N",chain,0)) return -1;
    if (firewall("-A",chain,1)) return -1;
    return 0;
}
static int resource_cleanup(int dir) {
    if (!exists(dir,CHAIN_INTENT)) { unlinkat(dir,"chain.intent.tmp",0); return 0; }
    char chain[32]; if (read_chain(dir,chain)) return -1;
    int present=firewall("-S",chain,0);
    if (present == 1) { unlinkat(dir,CHAIN_INTENT,0); return 0; }
    if (present) return -1;
    int own=firewall("-C",chain,1);
    if (own == 0 && firewall("-D",chain,1)) return -1;
    if (own != 0 && own != 1) return -1;
    /* -X refuses referenced or non-empty chains. Never use -F or restore a global snapshot. */
    if (firewall("-X",chain,0) && firewall("-S",chain,0) != 1) return -1;
    if (unlinkat(dir,CHAIN_INTENT,0)) return -1;
    unlinkat(dir,"chain.intent.tmp",0);
    return fsync(dir);
}
static int resource_pending(int dir) { return exists(dir,CHAIN_INTENT) || exists(dir,"chain.intent.tmp"); }
static int kernel_chain_present(int dir) {
    char chain[32];
    if (!exists(dir,CHAIN_INTENT)) return 0;
    if (read_chain(dir,chain)) return -1;
    int result=firewall("-S",chain,0);
    return result == 0 ? 1 : result == 1 ? 0 : -1;
}
