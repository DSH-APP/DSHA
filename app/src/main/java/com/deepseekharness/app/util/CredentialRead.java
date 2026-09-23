package com.deepseekharness.app.util;

/** 凭据读取结果不把失败伪装成空配置；诊断字符串不包含明文或原异常。 */
public final class CredentialRead {
    public enum State { NOT_CONFIGURED, AVAILABLE, TEMPORARILY_UNAVAILABLE, NEEDS_ATTENTION }
    public enum Reason { NONE, DEVICE_LOCKED, AUTHENTICATION_REQUIRED, TRANSIENT_STORE, KEY_MISSING, KEY_INVALIDATED, UNREADABLE }
    public final State state;public final Reason reason;private final String secret;
    private CredentialRead(State state,Reason reason,String secret){this.state=state;this.reason=reason;this.secret=secret;}
    public static CredentialRead missing(){return new CredentialRead(State.NOT_CONFIGURED,Reason.NONE,"");}
    public static CredentialRead available(String value){if(value==null||value.isEmpty())return failed(Reason.UNREADABLE);return new CredentialRead(State.AVAILABLE,Reason.NONE,value);}
    public static CredentialRead failed(Reason reason){
        if(reason==null||reason==Reason.NONE)throw new IllegalArgumentException();
        boolean temporary=reason==Reason.DEVICE_LOCKED||reason==Reason.AUTHENTICATION_REQUIRED||reason==Reason.TRANSIENT_STORE;
        return new CredentialRead(temporary?State.TEMPORARILY_UNAVAILABLE:State.NEEDS_ATTENTION,reason,null);
    }
    public boolean usable(){return state==State.AVAILABLE||state==State.NOT_CONFIGURED;}
    public String requireValue(){if(!usable())throw new Unavailable(this);return secret;}
    @Override public String toString(){return "CredentialRead{"+state+","+reason+"}";}
    public static final class Unavailable extends IllegalStateException {
        public final CredentialRead result;
        private Unavailable(CredentialRead result){super("CREDENTIAL_"+result.state.name());this.result=result;}
    }
}
