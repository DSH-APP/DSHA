/* SPDX-License-Identifier: MIT
 * Temporary native probe only. No app data, path values or business argv output.
 */
typedef unsigned long word;
static long call(long n, word a, word b, word c) {
  register word x0 __asm__("x0")=a, x1 __asm__("x1")=b, x2 __asm__("x2")=c;
  register long x8 __asm__("x8")=n;
  __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1),"r"(x2),"r"(x8) : "memory","cc");
  return (long)x0;
}
static int eq(const char *a,const char *b) { while(*a && *a==*b) {++a;++b;} return *a==*b; }
static void out(const char *s) { word n=0; while(s[n]) ++n; call(64,1,(word)s,n); }
static void number(word n) { char b[32]; word p=sizeof(b); do {b[--p]='0'+n%10;n/=10;}while(n); call(64,1,(word)(b+p),sizeof(b)-p); }
static int starts(const char *s,const char *prefix) {while(*prefix)if(*s++!=*prefix++)return 0;return 1;}
static word hex(const char **p) {word n=0;while(**p){char c=**p;word v=c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:16;if(v==16)break;n=n*16+v;++*p;}return n;}
static void mapping(const char *line,word pc,word *matched,word *bridge) {
  const char *p=line;word low=hex(&p);if(*p++!='-')return;word high=hex(&p);
  const char *path=p;while(*path && *path!='/')++path;
  const char *base=path;for(const char *at=path;*at;++at)if(*at=='/')base=at+1;
  if(starts(path,"/data/app/") && eq(base,"libproroot-bridge.so"))++*bridge;
  if(low>pc || high<=pc)return;
  ++*matched;out(" pc_map=");
  if(!*path)out("ANONYMOUS");
  else if(starts(path,"/data/app/"))out("FRAMEWORK_NATIVE_FILE");
  else if(starts(path,"/data/local/tmp/"))out("SHELL_TEMP_FILE");
  else out("OTHER_FILE");
  out(" pc_exec=");out(starts(p," r-x")?"RX":"OTHER");
}
void probe_start(word *stack) {
  char path[4096];
  register word x0 __asm__("x0")=(word)-100,x1 __asm__("x1")=(word)"/proc/self/exe",x2 __asm__("x2")=(word)path,x3 __asm__("x3")=4095;
  register long x8 __asm__("x8")=78;
  __asm__ volatile("svc 0":"+r"(x0):"r"(x1),"r"(x2),"r"(x3),"r"(x8):"memory","cc");
  out("DSHA_CONTEXT_PROBE exe=");
  if((long)x0<=0 || x0>=4095)out("READLINK_ERROR");
  else {path[x0]=0;char *base=path;for(char *p=path;*p;++p)if(*p=='/')base=p+1;
    if(eq(base,"linker64"))out("SYSTEM_LINKER64");
    else if(eq(base,"libproroot.so"))out("VENDOR_LAUNCHER");
    else if(eq(base,"libproroot-bridge.so"))out("VENDOR_BRIDGE");
    else if(eq(base,"libproroot-stub-loader.so"))out("VENDOR_STUB");
    else out("OTHER");
  }
  out(" argc=");number(stack[0]);out(" options=");
  char **argv=(char **)(stack+1);
  for(word i=0;i<stack[0] && i<16;++i) {
    if(i)out(",");
    if(eq(argv[i],"--argv0"))out("ARGV0_FLAG");
    else if(eq(argv[i],"--preload"))out("PRELOAD_FLAG");
    else out("VALUE");
  }
  word pc;__asm__ volatile("adr %0, .":"=r"(pc));
  long fd=call(56,(word)-100,(word)"/proc/self/maps",0);
  if(fd<0)out(" pc_map=MAPS_OPEN_ERROR");
  else {
    char buffer[512],line[4096];word at=0,matched=0,bridge=0,total=0;
    for(;;){long size=call(63,(word)fd,(word)buffer,sizeof(buffer));if(size<=0)break;
      for(long i=0;i<size;++i){if(++total>4194304)break;
        if(buffer[i]=='\n'){line[at]=0;mapping(line,pc,&matched,&bridge);at=0;}
        else if(at<sizeof(line)-1)line[at++]=buffer[i];
      }if(total>4194304)break;
    }
    call(57,(word)fd,0,0);out(" pc_matches=");number(matched);out(" native_bridge_segments=");number(bridge);
  }
  out("\n");
  call(94,0,0,0); __builtin_unreachable();
}
__asm__(".global _start\n_start:\nmov x0, sp\nb probe_start\n");
