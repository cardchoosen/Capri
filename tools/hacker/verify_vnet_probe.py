#!/usr/bin/env python3
"""Run the oversized-frame/VNET regression only inside a private namespace, without physical interfaces."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('adb','serial'):p.add_argument('--'+name,required=True)
    for name in ('ndk','output'):p.add_argument('--'+name,type=Path,required=True)
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    if (args.output/'report.json').exists():raise RuntimeError('Use a fresh output directory')
    adb=[args.adb,'-s',args.serial];remote='/data/local/tmp/capri-vnet-namespace-probe';before=None;pushed=False
    report={}
    def root(command):
        r=subprocess.run(adb+['shell','su -c '+shlex.quote(command)],capture_output=True,text=True,timeout=20);r.check_returncode();return r.stdout
    def baseline():
        return {k:root(v) for k,v in {'nat':'iptables -w 2 -t nat -S','filter':'iptables -w 2 -t filter -S','mangle':'iptables -w 2 -t mangle -S','routes':'ip -4 route show table all','rules':'ip -4 rule show','physical_link':'ip -4 addr show wlan0','parameters':'cat /proc/sys/net/ipv4/ip_forward /proc/sys/net/ipv4/conf/wlan0/rp_filter; getenforce'}.items()}
    try:
        assert root('if test -e '+remote+'; then printf exists; fi')==''
        before=baseline();compiler=args.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang';binary=args.output/'helper'
        subprocess.run([str(compiler),'-std=c11','-O2','-Wall','-Wextra','-Werror',str(Path(__file__).with_name('vnet_namespace_probe.c')),'-o',str(binary)],check=True)
        subprocess.run(adb+['push',str(binary),remote],check=True,capture_output=True);pushed=True;root('chmod 700 '+remote)
        r=subprocess.run(adb+['shell','su -c '+shlex.quote(remote)],capture_output=True,text=True,timeout=18)
        report['exit_code']=r.returncode;report['diagnostic']=r.stderr[:1000]
        if r.stdout.strip():report['native']=json.loads(r.stdout)
        r.check_returncode();report['passed']=True
    except BaseException as error:report['passed']=False;report['error']=str(error)[:400];raise
    finally:
        if pushed:root('rm '+remote);report['helper_removed']=True
        if before is not None:
            after=baseline();report['original_network_unchanged']=before==after
            if before!=after:report['passed']=False
        (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)
    if not report.get('passed'):raise SystemExit('VNET verification incomplete')


if __name__=='__main__':main()
