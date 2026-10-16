package dev.flint.invsync.probe;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.block.ShulkerBox;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.Component;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.util.*;
import java.util.function.*;

/** CI-only, controlled player doubles running inside a real Paper server with real NBT/item code. */
public final class InventoryProbe extends JavaPlugin {
    Object target;
    UUID id=UUID.randomUUID();
    @Override public void onEnable(){target=Bukkit.getPluginManager().getPlugin("NetworkInventorySync");waitFor(()->Boolean.TRUE.equals(field(target,"ready")),()->{
        Fake first=new Fake(id);seed(first);enter(first,()->{
            check(!blocked(first),"ready player frozen");first.main[7]=new ItemStack(Material.DIAMOND,32);
            leave(first,()->{Fake second=new Fake(id);second.dead=true;second.main[0]=new ItemStack(Material.STONE);enter(second,()->{
                check(second.main[0].isSimilar(first.main[0]),"NBT sword mismatch");check(second.main[7].getAmount()==32,"latest quit snapshot lost");
                check(second.armour[2].getType()==Material.DIAMOND_CHESTPLATE,"armour missing");check(second.hand.getType()==Material.TOTEM_OF_UNDYING,"offhand missing");
                check(second.ender[4].getAmount()==17,"ender chest missing");check(second.health==13.5&&second.food==17&&second.saturation==4&&second.exhaustion==2,"optional vitals missing");
                check(second.cursor.getType().isAir()&&second.main[1].getType()==Material.APPLE,"cursor not normalized on quit");
                check(second.level==17&&second.xp==.5f&&second.total==321&&second.selected==8,"experience/selection missing");
                check(second.main[0].getItemMeta().getPersistentDataContainer().get(new NamespacedKey("invsync","proof"),PersistentDataType.STRING).equals("retained"),"PDC missing");
                ShulkerBox box=(ShulkerBox)((BlockStateMeta)second.main[3].getItemMeta()).getBlockState();check(box.getInventory().getItem(9).getAmount()==17,"nested container missing");
                // Simulate vanilla clearing storage after an ordinary death. A normal
                // respawn must not replay the older database snapshot and resurrect drops.
                second.main=new ItemStack[36];
                call("respawn",PlayerRespawnEvent.class,new PlayerRespawnEvent(second.player,new Location(Bukkit.getWorlds().getFirst(),0,64,0),false));
                Bukkit.getScheduler().runTaskLater(this,()->{
                    try{
                        check(second.main[0]==null&&second.main[7]==null,"normal respawn resurrected pre-death items");
                        leave(second,()->{getLogger().info("INVSYNC_PROBE_OK: lifecycle, death-screen/respawn, freeze, latest snapshot, NBT/PDC, nested items, armour/offhand, ender chest, XP and selection");Bukkit.shutdown();});
                    }catch(Throwable e){fail(e);}
                },3L);
            });});
        });
    });}
    void seed(Fake p){
        ItemStack sword=new ItemStack(Material.DIAMOND_SWORD);ItemMeta meta=sword.getItemMeta();meta.displayName(Component.text("NBT proof"));meta.addEnchant(Enchantment.SHARPNESS,3,true);meta.getPersistentDataContainer().set(new NamespacedKey("invsync","proof"),PersistentDataType.STRING,"retained");sword.setItemMeta(meta);p.main[0]=sword;
        ItemStack shulker=new ItemStack(Material.SHULKER_BOX);BlockStateMeta m=(BlockStateMeta)shulker.getItemMeta();ShulkerBox box=(ShulkerBox)m.getBlockState();box.getInventory().setItem(9,new ItemStack(Material.EMERALD,17));m.setBlockState(box);shulker.setItemMeta(m);p.main[3]=shulker;
        p.armour[2]=new ItemStack(Material.DIAMOND_CHESTPLATE);p.hand=new ItemStack(Material.TOTEM_OF_UNDYING);p.ender[4]=new ItemStack(Material.EMERALD,17);p.level=17;p.xp=.5f;p.total=321;p.selected=8;p.health=13.5;p.food=17;p.saturation=4;p.exhaustion=2;p.cursor=new ItemStack(Material.APPLE,2);
    }
    void enter(Fake fake,Runnable ready){
        Bukkit.getScheduler().runTaskAsynchronously(this,()->{
            try{
                var event=new AsyncPlayerPreLoginEvent("ProbeUser",InetAddress.getLoopbackAddress(),id,false);call("preLogin",AsyncPlayerPreLoginEvent.class,event);call("preLoginResult",AsyncPlayerPreLoginEvent.class,event);
                check(event.getLoginResult()==AsyncPlayerPreLoginEvent.Result.ALLOWED,"prelogin denied: "+event.getKickMessage());
                Bukkit.getScheduler().runTask(this,()->{
                    try{check(blocked(fake),"pending player not frozen");call("join",PlayerJoinEvent.class,new PlayerJoinEvent(fake.player,Component.empty()));
                        Item item=proxy(Item.class,(p,m,a)->defaultValue(m.getReturnType()));var drop=new PlayerDropItemEvent(fake.player,item);Bukkit.getPluginManager().callEvent(drop);check(drop.isCancelled(),"loading item drop permitted");
                        if(fake.dead)Bukkit.getScheduler().runTaskLater(this,()->{
                            try{
                                check(blocked(fake)&&"LOADING".equals(phase()),"dead login activated too early");
                                check(fake.main[0].getType()==Material.STONE,"dead login applied inventory before respawn");
                                fake.dead=false;
                                call("respawn",PlayerRespawnEvent.class,new PlayerRespawnEvent(fake.player,new Location(Bukkit.getWorlds().getFirst(),0,64,0),false));
                            }catch(Throwable e){fail(e);}
                        },3L);
                        waitFor(()->"READY".equals(phase()),ready);
                    }catch(Throwable e){fail(e);}
                });
            }catch(Throwable e){Bukkit.getScheduler().runTask(this,()->fail(e));}
        });
    }
    void leave(Fake fake,Runnable done){fake.online=false;call("quit",PlayerQuitEvent.class,new PlayerQuitEvent(fake.player,Component.empty(),PlayerQuitEvent.QuitReason.DISCONNECTED));waitFor(()->session()==null,done);}
    Object session(){return ((Map<?,?>)field(target,"sessions")).get(id);}
    String phase(){Object s=session();return s==null?"absent":Objects.toString(field(s,"phase"));}
    boolean blocked(Fake fake){try{Method m=target.getClass().getDeclaredMethod("blocked",Player.class);m.setAccessible(true);return (boolean)m.invoke(target,fake.player);}catch(Exception e){throw new RuntimeException(e);}}
    void call(String method,Class<?> type,Object event){try{target.getClass().getMethod(method,type).invoke(target,event);}catch(Exception e){throw new RuntimeException(e);}}
    static Object field(Object object,String name){try{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(Exception e){throw new RuntimeException(e);}}
    void waitFor(BooleanSupplier test,Runnable success){new BukkitRunnable(){int ticks;public void run(){try{if(test.getAsBoolean()){cancel();success.run();}else if(++ticks>800){cancel();fail(new IllegalStateException("Probe timed out: "+phase()));}}catch(Throwable e){cancel();fail(e);}}}.runTaskTimer(this,1,1);}
    void fail(Throwable error){getLogger().severe("INVSYNC_PROBE_FAILED: "+error);error.printStackTrace();Bukkit.shutdown();}
    static void check(boolean condition,String reason){if(!condition)throw new IllegalStateException(reason);}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static Object defaultValue(Class<?> type){if(!type.isPrimitive())return null;if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;if(type==double.class)return 0d;if(type==short.class)return (short)0;if(type==byte.class)return (byte)0;if(type==char.class)return (char)0;return null;}
    static final class Fake {
        ItemStack[] main=new ItemStack[36],armour=new ItemStack[4],ender=new ItemStack[27];ItemStack hand=new ItemStack(Material.AIR),cursor=new ItemStack(Material.AIR);
        int level,total,selected,food=20;float xp,saturation=5,exhaustion;double health=20;boolean online=true,dead;final Player player;
        Fake(UUID id){
            Inventory chest=proxy(Inventory.class,(p,m,a)->switch(m.getName()){case "getContents"->ender;case "setContents"->{ender=((ItemStack[])a[0]).clone();yield null;}default->defaultValue(m.getReturnType());});
            PlayerInventory inventory=proxy(PlayerInventory.class,(p,m,a)->switch(m.getName()){
                case "getStorageContents"->main;case "setStorageContents"->{main=((ItemStack[])a[0]).clone();yield null;}
                case "getArmorContents"->armour;case "setArmorContents"->{armour=((ItemStack[])a[0]).clone();yield null;}
                case "getItemInOffHand"->hand;case "setItemInOffHand"->{hand=(ItemStack)a[0];yield null;}
                case "getHeldItemSlot"->selected;case "setHeldItemSlot"->{selected=(int)a[0];yield null;}
                case "addItem"->{Map<Integer,ItemStack> leftover=new HashMap<>();ItemStack[] input=(ItemStack[])a[0];for(int x=0;x<input.length;x++){int free=-1;for(int i=0;i<36;i++)if(main[i]==null||main[i].getType().isAir()){free=i;break;}if(free<0)leftover.put(x,input[x]);else main[free]=input[x];}yield leftover;}
                default->defaultValue(m.getReturnType());});
            player=proxy(Player.class,(p,m,a)->switch(m.getName()){
                case "isDead"->dead;case "getUniqueId"->id;case "getName"->"ProbeUser";case "isOnline"->online;case "getInventory"->inventory;case "getEnderChest"->chest;
                case "getItemOnCursor"->cursor;case "setItemOnCursor"->{cursor=a[0]==null?new ItemStack(Material.AIR):(ItemStack)a[0];yield null;}
                case "getLevel"->level;case "setLevel"->{level=(int)a[0];yield null;}case "getExp"->xp;case "setExp"->{xp=(float)a[0];yield null;}
                case "getTotalExperience"->total;case "setTotalExperience"->{total=(int)a[0];yield null;}case "getMaxHealth"->20d;case "getHealth"->health;case "setHealth"->{health=(double)a[0];yield null;}
                case "getFoodLevel"->food;case "setFoodLevel"->{food=(int)a[0];yield null;}
                case "getSaturation"->saturation;case "setSaturation"->{saturation=(float)a[0];yield null;}
                case "getExhaustion"->exhaustion;case "setExhaustion"->{exhaustion=(float)a[0];yield null;}
                case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];case "toString"->"ProbeUser";
                case "kick"->{online=false;throw new IllegalStateException("Probe player was kicked: "+Arrays.toString(a));}
                default->defaultValue(m.getReturnType());});
        }
    }
}
