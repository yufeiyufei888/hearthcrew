package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

/** A bounded preparation goal, not a prescribed crafting sequence. Experimental until gated. */
public class PrepareRequest extends TaskRecord {
    final Item output;
    io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Preparation limits=io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Preparation.standard();
    int radius=32;
    PrepareRequest scope(int radius,io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Preparation limits){
        if(radius<1||radius>32)throw new IllegalArgumentException("work radius 1..32");this.radius=radius;this.limits=java.util.Objects.requireNonNull(limits);return this;
    }
    final int count, minimumDurability;
    public PrepareRequest(String id,long deadline,Item output,int count,int minimumDurability) {
        this("hearthcrew_prepare",id,deadline,output,count,minimumDurability);
    }
    protected PrepareRequest(String tool,String id,long deadline,Item output,int count,int minimumDurability) {
        super(tool,id,deadline);
        if(count<1||count>64||minimumDurability<0)throw new IllegalArgumentException("preparation goal");
        this.output=output;this.count=count;this.minimumDurability=minimumDurability;
    }
}
