package io.github.yufeiyufei888.hearthcrew.kernel;

import java.util.*;

/** Background status reads cannot hold the user's command slot or consume its acknowledgement. */
public final class UiRequests {
    public record Pending(UUID id,String operation,long sequence,long deadline) {}
    private final Map<UUID,Pending> requests=new LinkedHashMap<>();
    private long sequence,applied;
    public static boolean readOnly(String op){return Set.of("status","detail","history").contains(op);}
    public boolean foregroundPending(){return requests.values().stream().anyMatch(p->!readOnly(p.operation));}
    public String foregroundOperation(){return requests.values().stream().filter(p->!readOnly(p.operation)).map(Pending::operation).findFirst().orElse("");}
    public Pending begin(String operation,long now,long timeout){
        boolean same=requests.values().stream().anyMatch(p->p.operation.equals(operation));
        if(same || operation.equals("status")&&foregroundPending() || !readOnly(operation)&&!operation.equals("stop")&&foregroundPending())return null;
        var p=new Pending(UUID.randomUUID(),operation,++sequence,now+timeout);requests.put(p.id,p);return p;
    }
    public Pending finish(UUID id){return requests.remove(id);}
    public boolean applyView(Pending p){if(p.sequence<applied)return false;applied=p.sequence;return true;}
    public List<Pending> expire(long now){var expired=requests.values().stream().filter(p->now>=p.deadline).toList();expired.forEach(p->requests.remove(p.id));return expired;}
    public void clear(){requests.clear();sequence=applied=0;}
}
