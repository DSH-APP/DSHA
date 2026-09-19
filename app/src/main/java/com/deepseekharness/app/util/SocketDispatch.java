package com.deepseekharness.app.util;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** 接受 socket 后先登记总配额；短请求、长流和反向 pump 各用独立有界执行资源。 */
public final class SocketDispatch implements AutoCloseable {
    private final int maximum;
    private final Set<Ticket> tickets=new HashSet<>();
    private final ThreadPoolExecutor shortPool,streamPool,pumpPool;
    private boolean closed;private int peak;
    private final AtomicLong accepted=new AtomicLong(),rejected=new AtomicLong();
    public SocketDispatch(String name,int maximum,int workers,int queued,int streams){
        if(maximum<1||workers<1||queued<1||streams<1)throw new IllegalArgumentException();this.maximum=maximum;
        shortPool=pool(name+"-request",workers,new ArrayBlockingQueue<>(queued));
        streamPool=pool(name+"-stream",streams,new SynchronousQueue<>());
        pumpPool=pool(name+"-pump",streams,new SynchronousQueue<>());
    }
    private static ThreadPoolExecutor pool(String name,int size,BlockingQueue<Runnable> queue){
        return new ThreadPoolExecutor(size,size,0,TimeUnit.MILLISECONDS,queue,r->{Thread thread=new Thread(r,name);thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized Ticket accept(Socket socket){
        if(closed||tickets.size()>=maximum){rejected.incrementAndGet();closeQuietly(socket);return null;}
        Ticket ticket=new Ticket(socket);tickets.add(ticket);accepted.incrementAndGet();peak=Math.max(peak,tickets.size());return ticket;
    }
    public final class Ticket implements AutoCloseable {
        public final Socket socket;public final long acceptedAt=System.nanoTime();
        private Socket companion;private volatile boolean ended;
        private Ticket(Socket socket){this.socket=socket;}
        public boolean ended(){return ended;}
        public synchronized void attach(Socket back)throws IOException{
            if(ended||companion!=null){closeQuietly(back);throw new IOException("SOCKET_OWNER_CLOSED");}companion=back;
        }
        public void detach(Socket back){synchronized(this){if(companion==back)companion=null;}closeQuietly(back);}
        @Override public void close(){Socket back;synchronized(this){if(ended)return;ended=true;back=companion;companion=null;}
            closeQuietly(socket);closeQuietly(back);synchronized(SocketDispatch.this){tickets.remove(this);}
        }
    }
    public boolean request(Ticket owner,Runnable work){return execute(shortPool,owner,work);}
    public boolean stream(Ticket owner,Runnable work){return execute(streamPool,owner,work);}
    private boolean execute(ThreadPoolExecutor pool,Ticket owner,Runnable work){
        if(owner.ended())return false;
        try{pool.execute(()->{if(owner.ended())return;try{work.run();}catch(Throwable failure){owner.close();}});return true;}
        catch(RejectedExecutionException full){rejected.incrementAndGet();return false;}
    }
    public Future<?> pump(Ticket owner,Runnable work)throws IOException{
        if(owner.ended())throw new IOException("SOCKET_OWNER_CLOSED");
        try{return pumpPool.submit(()->{if(owner.ended())return;try{work.run();}catch(Throwable failure){owner.close();}});}
        catch(RejectedExecutionException full){rejected.incrementAndGet();throw new IOException("SOCKET_STREAM_CAPACITY",full);}
    }
    public synchronized Map<String,Long> metrics(){return Map.of("connections",(long)tickets.size(),"peakConnections",(long)peak,"accepted",accepted.get(),"rejected",rejected.get(),
            "queued",(long)shortPool.getQueue().size(),"requestThreads",(long)shortPool.getPoolSize(),"streamThreads",(long)streamPool.getPoolSize(),"pumpThreads",(long)pumpPool.getPoolSize());}
    @Override public void close(){List<Ticket> copy;synchronized(this){if(closed)return;closed=true;copy=new ArrayList<>(tickets);}
        for(Ticket ticket:copy)ticket.close();shortPool.shutdownNow();streamPool.shutdownNow();pumpPool.shutdownNow();
    }
    public boolean awaitStopped(long millis)throws InterruptedException{
        long until=System.nanoTime()+millis*1000000L;
        for(ThreadPoolExecutor pool:List.of(shortPool,streamPool,pumpPool))if(!pool.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS))return false;return true;
    }
    private static void closeQuietly(Closeable value){if(value!=null)try{value.close();}catch(IOException ignored){}}
}
