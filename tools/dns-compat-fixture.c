/* 仅测试：用真实 libuv/getaddrinfo 边界模拟 AAAA 拒绝，不访问外网、不修改 resolv.conf。
 * 构建无 DT_NEEDED 的 arm64 glibc 测试预加载库；只在测试 Node 子进程内使用。 */
typedef unsigned int socklen_t;
struct sockaddr { unsigned short family; char data[14]; };
struct addrinfo { int flags, family, socktype, protocol; socklen_t addrlen;
    struct sockaddr *addr; char *canonname; struct addrinfo *next; };
struct sockaddr_in { unsigned short family, port; unsigned int addr; unsigned char zero[8]; };
struct sockaddr_in6 { unsigned short family, port; unsigned int flow; unsigned char addr[16]; unsigned int scope; };
extern int strcmp(const char *,const char *);
static struct sockaddr_in ip4={2,0,0x0100007f,{0}};
static struct sockaddr_in6 ip6={10,0,0,{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},0};
static struct addrinfo a4={0,2,1,6,sizeof(ip4),(struct sockaddr *)&ip4,0,0};
static struct addrinfo a6={0,10,1,6,sizeof(ip6),(struct sockaddr *)&ip6,0,&a4};
int getaddrinfo(const char *node,const char *service,const struct addrinfo *hints,struct addrinfo **out) {
    (void)service;
    if(!node)return -2;
    int family=hints?hints->family:0;
    if(strcmp(node,"refused.dsha.test")==0) { if(family!=2)return -3; *out=&a4; return 0; }
    if(strcmp(node,"dual.dsha.test")==0) { *out=family==2?&a4:&a6; return 0; }
    return -2;
}
void freeaddrinfo(struct addrinfo *value) { (void)value; }
