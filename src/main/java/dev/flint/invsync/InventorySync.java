package dev.flint.invsync;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InventorySync extends JavaPlugin implements Listener,CommandExecutor,TabCompleter {
    private final ConcurrentMap<UUID,Session> sessions=new ConcurrentHashMap<>();
    private final ConcurrentMap<AsyncPlayerPreLoginEvent,Session> logins=new ConcurrentHashMap<>();
    private Options options;
    private Store store;
    private Journal journal;
    private ThreadPoolExecutor io;
    private volatile boolean ready,stopping;
    private String minecraft;
    private final ArrayDeque<UUID> autosaveQueue=new ArrayDeque<>();
    private long nextAutosave;
    /**
     * Integration hook for actions such as cross-server teleport arrival. Returns true
     * only for a bound, online session whose initial save and current ownership have
     * been verified. Safe to call from any thread, but returns false off the Paper main
     * thread so no Bukkit player state is read asynchronously. Recheck immediately
     * before the action; this result does not reserve ownership for future ticks.
     */
    public boolean isReady(UUID playerId) {
        if (!Bukkit.isPrimaryThread() || !ready || stopping || options == null || playerId == null) return false;
        Session session = sessions.get(playerId);
        return session != null && session.phase == Session.Phase.READY && session.fresh(options)
            && session.bound != null && session.bound.isOnline() && playerId.equals(session.bound.getUniqueId());
    }
    @Override public void onEnable(){
        // Keep the login gate active even when configuration/storage initialization fails.
        Bukkit.getPluginManager().registerEvents(this,this);
        Objects.requireNonNull(getCommand("invsync")).setExecutor(this);Objects.requireNonNull(getCommand("invsync")).setTabCompleter(this);
        for(Player player:Bukkit.getOnlinePlayers())player.kick(net.kyori.adventure.text.Component.text("Inventory sync is initializing; please reconnect."));
        try{
            saveDefaultConfig();getConfig().options().copyDefaults(true);options=Options.read(getConfig());minecraft=Bukkit.getMinecraftVersion();
            if(!minecraft.equals("26.1.2"))throw new IllegalStateException("This build targets Paper/Purpur 26.1.2 only");
            try{Class.forName("io.papermc.paper.threadedregions.RegionizedServer");throw new IllegalStateException("Folia is not supported");}catch(ClassNotFoundException expected){}
            io=new ThreadPoolExecutor(options.threads(),options.threads(),0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(options.queueCapacity()),r->{Thread t=new Thread(r,"InventorySync-IO");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
            journal=new Journal(getDataFolder().toPath().resolve("recovery").resolve(options.group()),options.maxPayload());store=new Store(options);
            Bukkit.getPluginManager().registerEvents(new FreezeGuard(this),this);
            submit(()->{try{store.initialize(minecraft);ready=true;getLogger().info("Inventory storage ready for group "+options.group()+" on "+minecraft);}catch(Exception e){getLogger().severe("Storage initialization failed ("+e.getClass().getSimpleName()+"). Check database access and group compatibility; logins remain blocked.");}});
            Bukkit.getScheduler().runTaskTimerAsynchronously(this,this::heartbeat,20L,options.heartbeatSeconds()*20L);
            nextAutosave=System.nanoTime()+TimeUnit.SECONDS.toNanos(options.autosaveSeconds());
            Bukkit.getScheduler().runTaskTimer(this,this::autosave,1L,1L);
            Bukkit.getScheduler().runTaskTimer(this,()->{for(Session s:sessions.values())if((s.phase==Session.Phase.READY||s.phase==Session.Phase.LOADING)&&!s.fresh(options))fail(s,"Database verification timed out");},1L,1L);
        }catch(Exception e){ready=false;getLogger().severe("Inventory Sync is blocking logins: "+e.getMessage()+". Fix configuration and restart.");}
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void preLogin(AsyncPlayerPreLoginEvent event){
        if(event.getLoginResult()!=AsyncPlayerPreLoginEvent.Result.ALLOWED)return;
        if(!ready||stopping){deny(event,"not-ready");return;}
        Session s=new Session(event.getUniqueId());s.connection=event.getConnection();
        if(sessions.putIfAbsent(s.player,s)!=null){deny(event,"busy");return;}
        logins.put(event,s);
        try{
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(options.loginWait());
            while(true){
                try{Store.Acquired acquired=store.acquire(s.player);s.token=acquired.token();s.revision=acquired.revision();s.payload=acquired.payload();break;}
                catch(Store.Busy busy){if(System.nanoTime()>=deadline)throw busy;Thread.sleep(options.loginPoll());}
            }
            if(s.payload!=null)Snapshot.decode(s.payload,options,minecraft);
            s.verified=System.nanoTime();s.phase=Session.Phase.PENDING;
        }catch(Store.Busy e){deny(event,"busy");sessions.remove(s.player,s);}
        catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();deny(event,"failed");if(s.token!=null)abandon(s);else sessions.remove(s.player,s);getLogger().warning("Login sync failed for "+s.player+" ("+e.getClass().getSimpleName()+")");}
    }
    private void deny(AsyncPlayerPreLoginEvent event,String key){event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,message(key));}
    @EventHandler(priority=EventPriority.MONITOR)
    public void preLoginResult(AsyncPlayerPreLoginEvent event){Session s=logins.remove(event);if(s!=null&&event.getLoginResult()!=AsyncPlayerPreLoginEvent.Result.ALLOWED&&s.token!=null)abandon(s);}
    @EventHandler(priority=EventPriority.MONITOR)
    public void join(PlayerJoinEvent event){
        Player player=event.getPlayer();Session s=sessions.get(player.getUniqueId());
        if(s==null||s.phase!=Session.Phase.PENDING){player.kick(message("failed"));return;}
        s.bound=player;s.phase=Session.Phase.LOADING;
        Bukkit.getScheduler().runTask(this,()->load(s,player));
    }
    private void load(Session s,Player player){
        if(stopping||!player.isOnline()||s.phase!=Session.Phase.LOADING||s.applied)return;
        // A backend may retain a death screen from an earlier visit. Respawn can replace
        // the inventory, so restore authoritative items only after that transition.
        if(player.isDead()){s.waitingForRespawn=true;return;}
        s.waitingForRespawn=false;
        try{
            if(s.payload!=null)Snapshot.decode(s.payload,options,minecraft).apply(player,options);
            else if(!options.importFirst())Snapshot.empty().apply(player,options);
            s.applied=true;
            byte[] snapshot=Snapshot.capture(player,options).encode(options,minecraft);s.payload=snapshot;
            persist(s,snapshot,false,true);
        }catch(Exception e){fail(s,"Cannot load item snapshot: "+e.getClass().getSimpleName());}
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event){
        Session s=sessions.get(event.getPlayer().getUniqueId());
        if(s==null||(s.phase!=Session.Phase.READY&&s.phase!=Session.Phase.LOADING))return;
        s.bound=event.getPlayer();
        if(s.phase==Session.Phase.LOADING&&s.waitingForRespawn){
            s.waitingForRespawn=false;
            Bukkit.getScheduler().runTask(this,()->load(s,event.getPlayer()));
        }
        // Ordinary deaths after activation use vanilla drops/keepInventory. Never replay
        // an old pre-death autosave on normal respawn: that would duplicate dropped items.
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event){
        Session s=sessions.get(event.getPlayer().getUniqueId());if(s==null||s.bound!=event.getPlayer())return;
        if(s.phase==Session.Phase.READY)finish(s,event.getPlayer());
        else if((s.phase==Session.Phase.PENDING||s.phase==Session.Phase.LOADING)&&!s.applied)abandon(s);
        // LOADING with an in-flight initial commit is finalized after that commit.
        else if(s.phase==Session.Phase.LOADING&&s.applied)finish(s,event.getPlayer());
    }
    private void finish(Session s,Player player){
        s.phase=Session.Phase.CLOSING;
        try{
            normalizeCursor(player);
            byte[] snapshot=Snapshot.capture(player,options).encode(options,minecraft);s.payload=snapshot;persist(s,snapshot,true,false);
        }catch(Exception e){fail(s,"Final inventory capture failed: "+e.getClass().getSimpleName());}
    }
    private void normalizeCursor(Player player){
        player.closeInventory();
        if(options.storage()){
            var cursor=player.getItemOnCursor();
            if(!cursor.getType().isAir()){
                player.setItemOnCursor(null);
                var leftover=player.getInventory().addItem(cursor.clone());
                leftover.values().forEach(item->player.getWorld().dropItemNaturally(player.getLocation(),item));
            }
        }
    }
    private void persist(Session s,byte[] payload,boolean release,boolean activating){
        try{queue(s,()->{
            try{
                journal.write(new Journal.Entry(s.player,s.token,s.revision,payload));
                s.revision=store.save(s.player,s.token,s.revision,payload,release);s.verified=System.nanoTime();
                cleanupJournal(s.player,s.token);
                if(release){s.phase=Session.Phase.CLOSED;sessions.remove(s.player,s);}
                else if(activating)onMain(()->{if(s.phase==Session.Phase.LOADING&&s.bound!=null&&s.bound.isOnline())s.phase=Session.Phase.READY;});
            }catch(Exception e){fail(s,"Save failed: "+e.getClass().getSimpleName());}
            finally{s.saving.set(false);}
        });}catch(RejectedExecutionException e){fail(s,"Storage queue full");}
    }
    private void autosave(){
        if(!ready||stopping)return;
        if(System.nanoTime()>=nextAutosave&&autosaveQueue.isEmpty()){
            autosaveQueue.addAll(sessions.keySet());nextAutosave=System.nanoTime()+TimeUnit.SECONDS.toNanos(options.autosaveSeconds());
        }
        for(int i=0;i<options.autosaveBatch()&&!autosaveQueue.isEmpty();i++){
            Session s=sessions.get(autosaveQueue.removeFirst());
            if(s==null||s.phase!=Session.Phase.READY||s.bound==null||!s.saving.compareAndSet(false,true))continue;
            try{byte[] snapshot=Snapshot.capture(s.bound,options).encode(options,minecraft);s.payload=snapshot;persist(s,snapshot,false,false);}
            catch(Exception e){s.saving.set(false);fail(s,"Autosave capture failed: "+e.getClass().getSimpleName());}
        }
    }

    private void heartbeat(){
        if(!ready||stopping)return;
        for(Session s:sessions.values()){
            if(s.phase==Session.Phase.PENDING&&(System.nanoTime()-s.created>TimeUnit.SECONDS.toNanos(options.pendingSeconds())||(s.connection!=null&&!s.connection.isConnected()))){abandon(s);continue;}
            if((s.phase!=Session.Phase.READY&&s.phase!=Session.Phase.LOADING)||!s.heartbeating.compareAndSet(false,true))continue;
            try{queue(s,()->{try{if(s.phase==Session.Phase.READY||s.phase==Session.Phase.LOADING){store.heartbeat(s.player,s.token);s.verified=System.nanoTime();}}catch(Exception e){fail(s,"Session verification failed: "+e.getClass().getSimpleName());}finally{s.heartbeating.set(false);}});}
            catch(RejectedExecutionException e){s.heartbeating.set(false);fail(s,"Storage queue full");}
        }
    }
    private void abandon(Session s){
        synchronized(s){if(s.phase==Session.Phase.CLOSED||s.phase==Session.Phase.CLOSING||s.phase==Session.Phase.FAILED)return;s.phase=Session.Phase.CLOSING;}
        try{queue(s,()->{try{store.abandon(s.player,s.token);s.phase=Session.Phase.CLOSED;sessions.remove(s.player,s);}catch(Exception e){fail(s,"Could not release unused login lock");}});}catch(RejectedExecutionException e){fail(s,"Storage queue full releasing unused login");}
    }
    boolean blocked(Player player){
        Session s=sessions.get(player.getUniqueId());
        if(s==null||s.bound!=player||s.phase!=Session.Phase.READY)return true;
        if(!s.fresh(options)){fail(s,"Database verification timed out");return true;}return false;
    }
    void loading(Player player){player.sendActionBar(message("loading"));}
    private void fail(Session s,String reason){
        synchronized(s){if(s.phase==Session.Phase.FAILED||s.phase==Session.Phase.CLOSED)return;s.phase=Session.Phase.FAILED;}
        getLogger().severe(reason+" for "+s.player+". Ownership remains locked; inspect/replay/recover before reconnecting.");
        onMain(()->{
            if(s.bound!=null&&s.bound.isOnline()){
                if(s.applied){try{normalizeCursor(s.bound);byte[] payload=Snapshot.capture(s.bound,options).encode(options,minecraft);submit(()->{try{journal.write(new Journal.Entry(s.player,s.token,s.revision,payload));}catch(IOException e){getLogger().severe("Could not write recovery file for "+s.player);}});}catch(Exception ignored){}}
                s.bound.kick(message("failed"));
            }
        });
    }
    private net.kyori.adventure.text.Component message(String key){try{return MiniMessage.miniMessage().deserialize(getConfig().getString("messages."+key,"<red>Inventory synchronization is unavailable."));}catch(RuntimeException e){return net.kyori.adventure.text.Component.text("Inventory synchronization is unavailable; contact staff.");}}
    private void onMain(Runnable work){if(!stopping&&isEnabled()){if(Bukkit.isPrimaryThread())work.run();else Bukkit.getScheduler().runTask(this,work);}}
    private void queue(Session session,Runnable work){
        session.enqueue(io,work).whenComplete((v,error)->{if(error!=null)fail(session,"Storage task rejected or failed");});
    }
    private void cleanupJournal(UUID player,UUID token){try{journal.delete(player,token);}catch(IOException e){getLogger().warning("Snapshot committed; obsolete recovery file could not be removed for "+player);}}
    private boolean submit(Runnable work){try{io.execute(work);return true;}catch(RejectedExecutionException e){return false;}}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!sender.hasPermission("invsync.admin")){sender.sendMessage(message("no-permission"));return true;}
        if(args.length==1&&args[0].equalsIgnoreCase("status")){sender.sendMessage("Inventory Sync: ready="+ready+", server="+(options==null?"unconfigured":options.server())+", group="+(options==null?"unconfigured":options.group())+", sessions="+sessions.size()+", queued="+(io==null?0:io.getQueue().size()));return true;}
        if(!ready||stopping){sender.sendMessage(message("not-ready"));return true;}
        if(args.length<2){usage(sender);return true;}
        try{
            UUID player=UUID.fromString(args[1]);String action=args[0].toLowerCase(Locale.ROOT);
            if(Set.of("recover","restore","replay").contains(action)&&!(sender instanceof ConsoleCommandSender)){sender.sendMessage("Recovery/restore/replay are console-only; stop the owning server first.");return true;}
            if(Set.of("recover","restore","replay").contains(action)&&sessions.containsKey(player)){Session local=sessions.get(player);if(local.phase!=Session.Phase.FAILED&&local.phase!=Session.Phase.CLOSED){sender.sendMessage("Refused: local session is still active. Stop the owning server first.");return true;}}
            if(!Set.of("inspect","history","recover","restore","replay").contains(action)){usage(sender);return true;}
            if(!submit(()->admin(sender,action,player,args)))sender.sendMessage("Storage queue is full; try again.");
        }catch(IllegalArgumentException e){usage(sender);}return true;
    }
    private void admin(CommandSender sender,String action,UUID player,String[] args){
        String result;
        try{
            result=switch(action){
                case "inspect" -> Objects.toString(store.inspect(player),"No record in this group.");
                case "history" -> store.history(player).toString();
                case "recover" -> {if(args.length!=3)throw new IllegalArgumentException("recover <uuid> <current-revision>");store.recover(player,Long.parseLong(args[2]));sessions.remove(player);yield "Recovery acknowledged: record unlocked at the expected revision. Latest committed snapshot retained.";}
                case "replay" -> {if(args.length!=3)throw new IllegalArgumentException("replay <uuid> <owner-token>");UUID token=UUID.fromString(args[2]);Journal.Entry entry=journal.read(player,token);Snapshot.decode(entry.payload(),options,minecraft);long next=store.replay(player,token,entry.revision(),entry.payload());cleanupJournal(player,token);sessions.remove(player);yield "Recovery file committed and lock released at revision "+next;}
                case "restore" -> {
                    if(args.length!=4)throw new IllegalArgumentException("restore <uuid> <history-revision> <current-revision>");
                    long historical=Long.parseLong(args[2]),expected=Long.parseLong(args[3]);byte[] bytes=store.historyPayload(player,historical);Snapshot.decode(bytes,options,minecraft);
                    var acquired=store.acquire(player);
                    try{if(acquired.revision()!=expected)throw new Store.Fenced();long next=store.save(player,acquired.token(),expected,bytes,true);sessions.remove(player);yield "History restored as new revision "+next;}
                    catch(Exception e){store.abandon(player,acquired.token());throw e;}
                }
                default -> throw new IllegalArgumentException("Unknown action");
            };
        }catch(Exception e){result="Operation refused/failed ("+e.getClass().getSimpleName()+"): "+(e instanceof IllegalArgumentException?e.getMessage():"check lock/revision/storage; no automatic takeover");}
        String response=result;onMain(()->sender.sendMessage(response));
    }
    private void usage(CommandSender sender){sender.sendMessage("/invsync status | inspect <uuid> | history <uuid> | recover <uuid> <revision> | restore <uuid> <history-revision> <current-revision> | replay <uuid> <owner-token>");}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String label,String[] args){if(!s.hasPermission("invsync.admin")||args.length!=1)return List.of();return List.of("status","inspect","history","recover","restore","replay").stream().filter(v->v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();}
    @Override public void onDisable(){
        ready=false;
        if(options!=null&&io!=null){for(Session s:sessions.values()){
            if(s.bound!=null&&s.bound.isOnline()){
                // Let other quit listeners finish before the final inventory capture.
                s.bound.kick(message("shutdown"));
                // Fallback if quit delivery is suppressed because this plugin is disabling.
                if(s.phase==Session.Phase.READY||(s.phase==Session.Phase.LOADING&&s.applied))finish(s,s.bound);
                else if(!s.applied&&s.token!=null)abandon(s);
            }else if(s.phase==Session.Phase.PENDING&&s.token!=null)abandon(s);
        }}
        stopping=true;
        if(io!=null){io.shutdown();try{if(!io.awaitTermination(options.shutdownSeconds(),TimeUnit.SECONDS)){getLogger().severe("Storage shutdown timed out; unreleased sessions remain locked. Recovery files may be present.");io.shutdownNow();}}catch(InterruptedException e){Thread.currentThread().interrupt();io.shutdownNow();}}
        if(store!=null)store.close();
    }
}
