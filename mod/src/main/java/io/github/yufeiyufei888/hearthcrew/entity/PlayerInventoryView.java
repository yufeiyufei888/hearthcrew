package io.github.yufeiyufei888.hearthcrew.entity;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** A view of the 36 native inventory slots. Never owns a second inventory. */
public final class PlayerInventoryView extends SimpleContainer {
    private final Inventory backing;
    public PlayerInventoryView(Inventory backing) { super(0); this.backing = backing; }
    @Override public int getContainerSize() { return 36; }
    @Override public ItemStack getItem(int slot) { return backing.getItem(slot); }
    @Override public void setItem(int slot, ItemStack stack) { backing.setItem(slot, stack); }
    @Override public ItemStack removeItem(int slot, int amount) { return backing.removeItem(slot, amount); }
    @Override public ItemStack removeItemNoUpdate(int slot) { return backing.removeItemNoUpdate(slot); }
    @Override public void setChanged() { if (backing != null) backing.setChanged(); }
    @Override public void clearContent() { for (int i=0;i<36;i++) backing.setItem(i,ItemStack.EMPTY); }
    @Override public boolean isEmpty() { for(int i=0;i<36;i++) if(!getItem(i).isEmpty())return false; return true; }
    @Override public int countItem(net.minecraft.world.item.Item item){int n=0;for(int i=0;i<36;i++)if(getItem(i).is(item))n+=getItem(i).getCount();return n;}
    @Override public boolean hasAnyOf(java.util.Set<net.minecraft.world.item.Item> items){for(int i=0;i<36;i++)if(items.contains(getItem(i).getItem())&&!getItem(i).isEmpty())return true;return false;}
    @Override public net.minecraft.nbt.ListTag createTag(net.minecraft.core.HolderLookup.Provider registry){var list=new net.minecraft.nbt.ListTag();for(int i=0;i<36;i++){var stack=getItem(i);if(!stack.isEmpty()){var tag=new net.minecraft.nbt.CompoundTag();tag.putInt("slot",i);tag.put("stack",stack.save(registry));list.add(tag);}}return list;}
    @Override public java.util.List<ItemStack> removeAllItems(){var all=new java.util.ArrayList<ItemStack>();for(int i=0;i<36;i++){if(!getItem(i).isEmpty())all.add(removeItemNoUpdate(i));}return all;}
    @Override public boolean canAddItem(ItemStack stack) {
        if(stack.isEmpty())return true;
        for(int i=0;i<36;i++){var at=getItem(i);if(at.isEmpty()||ItemStack.isSameItemSameComponents(at,stack)&&at.getCount()<at.getMaxStackSize())return true;}
        return false;
    }
    @Override public ItemStack addItem(ItemStack stack) {
        var left=stack.copy();
        for(int pass=0;pass<2;pass++)for(int i=0;i<36&&!left.isEmpty();i++){
            var at=getItem(i);
            if(pass==0&&!at.isEmpty()&&ItemStack.isSameItemSameComponents(at,left)){
                int n=Math.min(left.getCount(),at.getMaxStackSize()-at.getCount());at.grow(n);left.shrink(n);
            }else if(pass==1&&at.isEmpty())setItem(i,left.split(Math.min(left.getCount(),left.getMaxStackSize())));
        }
        setChanged();return left;
    }
}
