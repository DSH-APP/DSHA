#!/usr/bin/env python3
from pathlib import Path
import argparse
from asset_deployment import ROOT,load_manifest,verify_reader_contracts
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--source',default=str(ROOT/'app/src/main/assets'));parser.add_argument('--manifest');args=parser.parse_args()
    count=verify_reader_contracts(Path(args.source),load_manifest(args.manifest) if args.manifest else load_manifest())
    print('PASS explicit APK asset deployment:',count,'source files plus generated/external reader contracts')
if __name__=='__main__':main()
