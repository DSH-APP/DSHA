# DSHA 的终端入口与 TLS 根证书；用户显式设置的证书路径优先。
case ":$PATH:" in *:/root/dsh-bin:*) ;; *) export PATH="/root/dsh-bin:$PATH" ;; esac
export SSL_CERT_FILE="${SSL_CERT_FILE:-/usr/local/share/dsha/ca-certificates.crt}"
export REQUESTS_CA_BUNDLE="${REQUESTS_CA_BUNDLE:-$SSL_CERT_FILE}"
export CURL_CA_BUNDLE="${CURL_CA_BUNDLE:-$SSL_CERT_FILE}"
export GIT_SSL_CAINFO="${GIT_SSL_CAINFO:-$SSL_CERT_FILE}"
export NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$SSL_CERT_FILE}"
export npm_config_cafile="${npm_config_cafile:-$SSL_CERT_FILE}"
export npm_config_prefix="${npm_config_prefix:-/usr/local}"
export NARB_DISABLE_NATIVE_CACHE="${NARB_DISABLE_NATIVE_CACHE:-1}"
export NARB_DISABLE_NATIVE_CACHE="${NARB_DISABLE_NATIVE_CACHE:-1}"
