import com.deepseekharness.app.util.RecoveryLocalePolicy;
import com.deepseekharness.app.util.WebUploadSessionBudget;

public final class WebUiHostFixture {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        WebUploadSessionBudget budget=new WebUploadSessionBudget();
        for(int i=0;i<2;i++){
            var batch=budget.beginBatch(20);
            budget.addBytes(batch,256L*1024L*1024L);
            budget.finishCopy(batch);
            check(budget.commit(batch),"valid batch must commit");
        }
        check(budget.committedFiles()==40&&budget.committedBytes()==512L*1024L*1024L,"session budget accounting");
        try{budget.beginBatch(1);throw new AssertionError("41st file must be refused");}
        catch(WebUploadSessionBudget.LimitExceededException expected){}

        WebUploadSessionBudget cancelled=new WebUploadSessionBudget();
        var inFlight=cancelled.beginBatch(1);cancelled.addBytes(inFlight,17);cancelled.close();
        check(!cancelled.canDeleteOwnedFiles(),"do not delete while copy is active");
        try{cancelled.addBytes(inFlight,1);throw new AssertionError("closed session must stop copying");}
        catch(WebUploadSessionBudget.SessionClosedException expected){}
        cancelled.rollback(inFlight);
        check(cancelled.canDeleteOwnedFiles()&&!cancelled.commit(inFlight),"cancelled copy releases and cannot commit late");

        check("http://localhost:3081/auth?token=x%2F127.0.0.1#home".equals(
                RecoveryLocalePolicy.toLocalhostUrl("http://127.0.0.1:3081/auth?token=x%2F127.0.0.1#home")),
                "only authority changes while preserving raw credential query bytes");
        check(RecoveryLocalePolicy.sameOrigin("http://localhost:3081/","http://localhost:3081/chat?token=secret"),
                "same localhost authority is accepted");
        check(!RecoveryLocalePolicy.sameOrigin("http://localhost:3081/","http://127.0.0.1:3081/chat"),
                "formal origin is rejected by emergency bridge");
        check(!RecoveryLocalePolicy.sameOrigin("http://localhost:3081/","http://localhost:3082/chat"),
                "wrong local port is rejected");
        System.out.println("PASS WebUploadSessionBudget boundaries/cancellation; RecoveryLocalePolicy localhost authority/query");
    }
}
