package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 单线程 writer 的提交门与有界 drain；关闭提交门和插入 barrier 在同一监视器内完成。 */
public final class ExecutorQuiescence {
    private boolean closed;

    public synchronized boolean execute(ExecutorService executor,Runnable work) {
        if(closed)return false;
        executor.execute(work);return true;
    }

    public void closeAndAwait(ExecutorService executor,long timeoutMillis)throws IOException {
        Future<?> barrier;
        synchronized(this){closed=true;try{barrier=executor.submit(()->{});}
            catch(RuntimeException rejected){throw new IOException("EXECUTOR_QUIESCE_REJECTED",rejected);}}
        try{barrier.get(Math.max(0,timeoutMillis),TimeUnit.MILLISECONDS);}
        catch(TimeoutException timeout){barrier.cancel(false);throw new IOException("EXECUTOR_QUIESCE_TIMEOUT",timeout);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("EXECUTOR_QUIESCE_INTERRUPTED");}
        catch(java.util.concurrent.ExecutionException failed){throw new IOException("EXECUTOR_QUIESCE_FAILED",failed.getCause());}
    }

    public synchronized void reopen(){closed=false;}
    public synchronized boolean isClosed(){return closed;}
}
