#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#ifdef __linux__
#include <sys/prctl.h>
#endif

/* Prototype ONLY: default file leases; explicit build flag tests unreferenced chains. Never handles traffic. */
#ifdef CAPRI_FIREWALL_PROBE
#define ROOT "/data/local/tmp/capri-firewall-lifecycle"
#define NETWORK_CHANGES 1
#else
#define ROOT "/data/local/tmp/capri-net-lifecycle"
#define NETWORK_CHANGES 0
#endif
static volatile sig_atomic_t interrupted;
static void on_signal(int value) { (void)value; interrupted = 1; }
static long long clock_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) exit(90);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
static void pause_ms(void) {
    struct timespec ts = {0, 50000000}; nanosleep(&ts, NULL);
}
static int directory(void) {
    if (geteuid() != 0) { fprintf(stderr,"root_required\n"); return -1; }
    if (mkdir(ROOT, 0700) && errno != EEXIST) return -1;
    int fd = open(ROOT, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    if (fd < 0) return -1;
    if (fstat(fd, &st) || st.st_uid != 0 || (st.st_mode & 0777) != 0700) {
        close(fd); fprintf(stderr,"unsafe_state_directory\n"); return -1;
    }
    return fd;
}
static int safe_file(int dir, const char *name, int flags) {
    int fd = openat(dir, name, flags | O_NOFOLLOW | O_CLOEXEC, 0600);
    struct stat st;
    if (fd < 0) return -1;
    if (fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_uid != 0 || st.st_nlink != 1) {
        close(fd); return -1;
    }
    return fd;
}
static int marker(int dir, const char *name) {
    int fd = safe_file(dir, name, O_WRONLY | O_CREAT | O_EXCL);
    if (fd < 0) return -1;
    close(fd); return 0;
}
static int write_state(int dir, const char *status, const char *reason, int seconds) {
    unlinkat(dir,"state.tmp",0);
    int fd = safe_file(dir,"state.tmp", O_WRONLY | O_CREAT | O_EXCL);
    if (fd < 0) return -1;
    char data[512];
    int size = snprintf(data,sizeof(data),"{\"prototype\":true,\"status\":\"%s\",\"reason\":\"%s\",\"limit_seconds\":%d,\"network_changes\":%d}\n", status,reason,seconds,NETWORK_CHANGES);
    int good = write(fd,data,(size_t)size) == size && fsync(fd) == 0;
    close(fd);
    if (!good || renameat(dir,"state.tmp",dir,"state.json")) return -1;
    return fsync(dir);
}
static int lock_job(int dir) {
    int fd = safe_file(dir,"job.lock",O_RDWR | O_CREAT);
    if (fd < 0) return -1;
    if (flock(fd,LOCK_EX | LOCK_NB)) { close(fd); return -1; }
    return fd;
}
static int exists(int dir, const char *name) {
    struct stat st; return fstatat(dir,name,&st,AT_SYMLINK_NOFOLLOW) == 0;
}
#ifdef CAPRI_FIREWALL_PROBE
#include "firewall_resource.h"
#else
static int resource_apply(int dir) { return marker(dir,"simulated-resource"); }
static int resource_cleanup(int dir) { unlinkat(dir,"simulated-resource",0); return 0; }
static int resource_pending(int dir) { return exists(dir,"simulated-resource"); }
#endif
static int cleanup(int dir) {
    if (resource_cleanup(dir)) return -1;
    unlinkat(dir,"stop.request",0);
    unlinkat(dir,"state.tmp",0);
    return fsync(dir);
}
static int show(int dir) {
    int lock = lock_job(dir);
    int held = lock < 0;
    if (lock >= 0) close(lock);
    char state[1024] = "null";
    int fd = safe_file(dir,"state.json",O_RDONLY);
    if (fd >= 0) { ssize_t n=read(fd,state,sizeof(state)-1); close(fd); if (n >= 0) state[n]=0; }
    printf("{\"lock_held\":%s,\"resource_present\":%s,\"simulated_resource_present\":%s,\"state\":%s", held?"true":"false",resource_pending(dir)?"true":"false",(!NETWORK_CHANGES && resource_pending(dir))?"true":"false",state);
#ifdef CAPRI_FIREWALL_PROBE
    printf(",\"kernel_chain_present\":%d",kernel_chain_present(dir));
#endif
    printf("}\n");
    return 0;
}
static int run(int dir, int seconds, const char *fault) {
    int lock = lock_job(dir);
    if (lock < 0) { fprintf(stderr,"job_busy_or_lock_error\n"); return 3; }
    if (resource_pending(dir)) {
        close(lock); fprintf(stderr,"recovery_required\n"); return 4;
    }
    if (cleanup(dir)) { close(lock); return 8; }
    if (write_state(dir,"running","none",seconds)) { close(lock); return 5; }
    signal(SIGCHLD,SIG_DFL);
    signal(SIGTERM,on_signal); signal(SIGINT,on_signal);
    signal(SIGHUP,SIG_IGN); signal(SIGPIPE,SIG_IGN);
    pid_t supervisor = getpid();
    pid_t child = fork();
    if (child < 0) { cleanup(dir); write_state(dir,"error","fork_failed",seconds); close(lock); return 6; }
    if (child == 0) {
#ifdef __linux__
        if (prctl(PR_SET_PDEATHSIG,SIGTERM) != 0) _exit(60);
#endif
        if (getppid() != supervisor || interrupted) _exit(61);
        if (resource_apply(dir)) _exit(62);
        long long start=clock_ms();
        while (!interrupted && getppid() == supervisor) {
            if (!strcmp(fault,"worker_exit") && clock_ms()-start >= 250) _exit(70);
            pause_ms();
        }
        _exit(cleanup(dir)?63:0);
    }
    long long start=clock_ms();
    const char *reason="unknown";
    int status=0;
    for (;;) {
        pid_t ended=waitpid(child,&status,WNOHANG);
        if (ended == child) { reason="worker_exit"; break; }
        if (ended < 0 && errno != EINTR) { reason="wait_error"; break; }
        if (!strcmp(fault,"supervisor_exit") && clock_ms()-start >= 500) _exit(71);
#ifdef CAPRI_FIREWALL_PROBE
        if (!strcmp(fault,"both_exit") && clock_ms()-start >= 700) {
            kill(child,SIGKILL);
            while (waitpid(child,&status,0)<0 && errno==EINTR) {}
            _exit(72);
        }
#endif
        if (interrupted || exists(dir,"stop.request")) { reason="stop_requested"; break; }
        if (clock_ms()-start >= (long long)seconds*1000) { reason="deadline"; break; }
        pause_ms();
    }
    if (strcmp(reason,"worker_exit") && strcmp(reason,"wait_error")) {
        kill(child,SIGTERM);
        long long grace=clock_ms();
        pid_t ended;
        do { ended=waitpid(child,&status,WNOHANG); if (ended != 0) break; pause_ms(); } while (clock_ms()-grace < 1000);
        if (ended == 0) { kill(child,SIGKILL); while (waitpid(child,&status,0)<0 && errno==EINTR) {} }
    }
    if (cleanup(dir)) { write_state(dir,"error","cleanup_conflict",seconds); close(lock); return 8; }
    int result=write_state(dir,!strcmp(reason,"wait_error")?"error":"stopped",reason,seconds);
    close(lock);
    if (result) return 7;
    return show(dir);
}
int main(int argc,char **argv) {
    if (argc < 2) return 64;
    int seconds=0;
    const char *fault="none";
    if (!strcmp(argv[1],"run")) {
        if (argc < 3 || argc > 4) return 64;
        char *end=NULL; long number=strtol(argv[2],&end,10);
        if (!end || *end || number < 1 || number > 30) return 64;
        seconds=(int)number;
        if (argc == 4) fault=argv[3];
        if (strcmp(fault,"none") && strcmp(fault,"worker_exit") && strcmp(fault,"supervisor_exit")
#ifdef CAPRI_FIREWALL_PROBE
            && strcmp(fault,"both_exit")
#endif
        ) return 64;
    } else if (argc != 2) return 64;
    if (strcmp(argv[1],"run") && strcmp(argv[1],"status") && strcmp(argv[1],"stop") && strcmp(argv[1],"recover") && strcmp(argv[1],"purge")) return 64;
    int dir=directory(); if (dir < 0) return 65;
    int result=0;
    if (!strcmp(argv[1],"run")) result=run(dir,seconds,fault);
    else if (!strcmp(argv[1],"status")) result=show(dir);
    else if (!strcmp(argv[1],"stop")) {
        int lock=lock_job(dir);
        if (lock >= 0) { close(lock); result=show(dir); }
        else if (marker(dir,"stop.request") && errno != EEXIST) result=66;
    } else if (!strcmp(argv[1],"recover") || !strcmp(argv[1],"purge")) {
        int lock=lock_job(dir);
        if (lock < 0) result=3;
        else {
            if (cleanup(dir)) { close(lock); close(dir); return 8; }
            if (!strcmp(argv[1],"recover")) { result=write_state(dir,"recovered","no_active_lease",0); }
            else { unlinkat(dir,"state.json",0); unlinkat(dir,"job.lock",0); }
            close(lock);
            if (!strcmp(argv[1],"purge")) result=rmdir(ROOT);
            else if (!result) result=show(dir);
        }
    } else result=64;
    close(dir); return result;
}
