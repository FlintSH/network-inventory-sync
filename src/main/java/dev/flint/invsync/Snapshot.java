package dev.flint.invsync;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import java.io.*;
import java.util.*;

/** Versioned, bounded NBT byte payload. No Java object deserialization. Bukkit access stays on main. */
record Snapshot(byte[][] storage, byte[][] armour, byte[][] offhand, byte[][] ender, byte[][] cursor,
                int level, int totalExperience, float experience, int selected, double health,
                int food, float saturation, float exhaustion) {
    private static final int MAGIC = 0x4e495331;
    static Snapshot capture(Player player, Options o) {
        requireMain();
        var inv=player.getInventory();
        return new Snapshot(items(o.storage()?inv.getStorageContents():new ItemStack[36]), items(o.armour()?inv.getArmorContents():new ItemStack[4]),
            items(new ItemStack[]{o.offhand()?inv.getItemInOffHand():null}),items(o.ender()?player.getEnderChest().getContents():new ItemStack[27]),
            items(new ItemStack[]{o.storage()?player.getItemOnCursor():null}),o.experience()?player.getLevel():0,o.experience()?player.getTotalExperience():0,
            o.experience()?player.getExp():0,o.selected()?inv.getHeldItemSlot():0,o.health()?player.getHealth():20,
            o.hunger()?player.getFoodLevel():20,o.hunger()?player.getSaturation():5,o.hunger()?player.getExhaustion():0);
    }
    static Snapshot empty() { return new Snapshot(new byte[36][],new byte[4][],new byte[1][],new byte[27][],new byte[1][],0,0,0,0,20,20,5,0); }
    void apply(Player player,Options o) throws IOException {
        requireMain();
        // Deserialize every enabled slot before mutating any inventory.
        ItemStack[] main=o.storage()?restore(storage):null, armor=o.armour()?restore(armour):null,
            hand=o.offhand()?restore(offhand):null, chest=o.ender()?restore(ender):null, carried=o.storage()?restore(cursor):null;
        player.closeInventory();
        if(o.storage()){player.getInventory().setStorageContents(main);player.setItemOnCursor(carried[0]);}
        if(o.armour())player.getInventory().setArmorContents(armor);
        if(o.offhand())player.getInventory().setItemInOffHand(hand[0]);
        if(o.ender())player.getEnderChest().setContents(chest);
        if(o.experience()){player.setLevel(level);player.setExp(experience);player.setTotalExperience(totalExperience);}
        if(o.selected())player.getInventory().setHeldItemSlot(selected);
        if(o.health()&&!player.isDead())player.setHealth(Math.min(Math.max(0.01,health),player.getMaxHealth()));
        if(o.hunger()){player.setFoodLevel(food);player.setSaturation(saturation);player.setExhaustion(exhaustion);}
        player.updateInventory();
    }
    byte[] encode(Options o,String minecraft) throws IOException {
        validate(level,totalExperience,experience,selected,health,food,saturation,exhaustion);
        if(storage.length!=36||armour.length!=4||offhand.length!=1||ender.length!=27||cursor.length!=1)throw new IOException("Wrong slot count");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeUTF(minecraft);out.writeUTF(o.profile());
            for(byte[][] slots:List.of(storage,armour,offhand,ender,cursor)){out.writeInt(slots.length);for(byte[] item:slots){out.writeInt(item==null?0:item.length);if(item!=null)out.write(item);}}
            out.writeInt(level);out.writeInt(totalExperience);out.writeFloat(experience);out.writeInt(selected);out.writeDouble(health);out.writeInt(food);out.writeFloat(saturation);out.writeFloat(exhaustion);
        }
        if(bytes.size()>o.maxPayload())throw new IOException("Snapshot exceeds max-payload-bytes");
        return bytes.toByteArray();
    }
    static Snapshot decode(byte[] bytes,Options o,String minecraft)throws IOException {
        if(bytes==null||bytes.length>o.maxPayload())throw new IOException("Invalid payload size");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=MAGIC||!in.readUTF().equals(minecraft)||!in.readUTF().equals(o.profile()))throw new IOException("Snapshot schema/version/profile mismatch");
            byte[][] storage=read(in,36,o),armour=read(in,4,o),offhand=read(in,1,o),ender=read(in,27,o),cursor=read(in,1,o);
            int level=in.readInt(),total=in.readInt();float xp=in.readFloat();int selected=in.readInt();double health=in.readDouble();int food=in.readInt();float saturation=in.readFloat(),exhaustion=in.readFloat();
            validate(level,total,xp,selected,health,food,saturation,exhaustion);
            if(in.available()!=0)throw new IOException("Trailing snapshot data");
            return new Snapshot(storage,armour,offhand,ender,cursor,level,total,xp,selected,health,food,saturation,exhaustion);
        }catch(IllegalArgumentException e){throw new IOException("Invalid snapshot",e);}
    }
    private static void validate(int level,int total,float xp,int selected,double health,int food,float saturation,float exhaustion)throws IOException{
        if(level<0||total<0||!Float.isFinite(xp)||xp<0||xp>1||selected<0||selected>8||!Double.isFinite(health)||health<0||health>1_000_000||food<0||food>20||!Float.isFinite(saturation)||saturation<0||saturation>20||!Float.isFinite(exhaustion)||exhaustion<0||exhaustion>40)throw new IOException("Invalid snapshot values");
    }
    private static byte[][] read(DataInputStream in,int count,Options o)throws IOException {
        if(in.readInt()!=count)throw new IOException("Wrong slot count");byte[][] result=new byte[count][];
        for(int i=0;i<count;i++){int size=in.readInt();if(size<0||size>o.maxPayload()||size>in.available())throw new IOException("Invalid item size");if(size>0){result[i]=in.readNBytes(size);if(result[i].length!=size)throw new EOFException();}}
        return result;
    }
    private static byte[][] items(ItemStack[] stacks){byte[][] bytes=new byte[stacks.length][];for(int i=0;i<stacks.length;i++)if(stacks[i]!=null&&!stacks[i].getType().isAir())bytes[i]=stacks[i].serializeAsBytes();return bytes;}
    private static ItemStack[] restore(byte[][] bytes)throws IOException {
        ItemStack[] result=new ItemStack[bytes.length];try{for(int i=0;i<bytes.length;i++)if(bytes[i]!=null)result[i]=ItemStack.deserializeBytes(bytes[i]);}catch(RuntimeException e){throw new IOException("Item NBT is incompatible or corrupt",e);}return result;
    }
    private static void requireMain(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Inventory access must run on the server thread");}
}
