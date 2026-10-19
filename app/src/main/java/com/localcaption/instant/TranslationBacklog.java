package com.localcaption.instant;

import java.util.LinkedHashMap;

/** Bounded by distinct utterances; revisions replace drafts without extending their deadline. */
final class TranslationBacklog {
    static final int MAX_PENDING=8;
    static final long MAX_WAIT_MS=15_000;
    static final class Request {
        final long id,epoch,queuedAt;
        final int revision;
        final String text;
        Request(long id,String text,int revision,long epoch,long now){this.id=id;this.text=text;this.revision=revision;this.epoch=epoch;queuedAt=now;}
    }
    private final LinkedHashMap<Long,Request> requests=new LinkedHashMap<>();
    boolean offer(long id,String text,int revision,long epoch,long now) {
        Request old=requests.get(id);
        if(old==null&&requests.size()>=MAX_PENDING)return false;
        requests.put(id,new Request(id,text,revision,epoch,old==null?now:old.queuedAt));return true;
    }
    Request poll(){if(requests.isEmpty())return null;Long id=requests.keySet().iterator().next();return requests.remove(id);}
    boolean superseded(Request r){Request latest=requests.get(r.id);return latest!=null&&latest.revision>r.revision;}
    boolean isEmpty(){return requests.isEmpty();}
    int size(){return requests.size();}
    void clear(){requests.clear();}
}
