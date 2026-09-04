package me.crystalsmp.crystalamethyst;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.AmethystCluster;
import org.bukkit.command.*;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class CrystalAmethyst extends JavaPlugin implements Listener {
    private Economy economy;
    private Object playerPointsApi;
    private NamespacedKey typeKey, specialKey;
    private final Map<String, Growth> growing = new HashMap<>();
    private final Map<String, UUID> specialDisplays = new HashMap<>();
    private final Set<String> generatedGeodes = new HashSet<>();

    private record Growth(long requiredMillis, long progressMillis, long lastUpdate) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "item_type");
        specialKey = new NamespacedKey(this, "special_amethyst");
        setupVault(); setupPlayerPoints(); registerRecipes();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("crystalamethyst")).setExecutor(this::command);
        getServer().getScheduler().runTaskTimer(this, this::tickGrowth, 20L, 20L);
    }
    private void setupVault(){ RegisteredServiceProvider<Economy> r=getServer().getServicesManager().getRegistration(Economy.class); if(r!=null)economy=r.getProvider(); }
    private void setupPlayerPoints(){ var p=getServer().getPluginManager().getPlugin("PlayerPoints"); if(p!=null)try{playerPointsApi=p.getClass().getMethod("getAPI").invoke(p);}catch(Exception ignored){} }

    private ItemStack item(String type,String name,Material mat){ ItemStack i=new ItemStack(mat); ItemMeta m=i.getItemMeta(); m.setDisplayName(ChatColor.translateAlternateColorCodes('&',name)); m.getPersistentDataContainer().set(typeKey,PersistentDataType.STRING,type); i.setItemMeta(m); return i; }
    private String type(ItemStack i){ if(i==null||!i.hasItemMeta())return null; return i.getItemMeta().getPersistentDataContainer().get(typeKey,PersistentDataType.STRING); }
    private ItemStack seed(){return item("seed","&d&lАметистовое семя",Material.AMETHYST_SHARD);}
    private ItemStack special(){return item("amethyst","&5&l✦ Особый аметист",Material.AMETHYST_CLUSTER);}
    private ItemStack crystal(){return item("crystal","&d&lКристальная монета",Material.ECHO_SHARD);}
    private ItemStack amCoin(){return item("amethystcoin","&5&lАметистовая монета",Material.ECHO_SHARD);}
    private String key(Block b){return b.getWorld().getUID()+":"+b.getX()+":"+b.getY()+":"+b.getZ();}

    @EventHandler(ignoreCancelled=true)
    public void onChunk(ChunkPopulateEvent e){
        Chunk c=e.getChunk(); World w=c.getWorld();
        // Process each natural geode once. A geode is detected by its budding-amethyst core.
        for(int x=c.getX()*16;x<c.getX()*16+16;x++) for(int y=w.getMinHeight();y<w.getMaxHeight();y++) for(int z=c.getZ()*16;z<c.getZ()*16+16;z++){
            Block b=w.getBlockAt(x,y,z); if(b.getType()!=Material.BUDDING_AMETHYST) continue;
            String g=geodeKey(b); if(generatedGeodes.add(g)) trySpawnSpecial(b);
        }
    }
    private String geodeKey(Block b){
        // Use the nearest budding-amethyst cell as a stable identifier; all buds in one vanilla geode are connected.
        int r=8; Block best=b; double bd=Double.MAX_VALUE;
        for(int x=b.getX()-r;x<=b.getX()+r;x++) for(int y=Math.max(b.getWorld().getMinHeight(),b.getY()-r);y<=Math.min(b.getWorld().getMaxHeight()-1,b.getY()+r);y++) for(int z=b.getZ()-r;z<=b.getZ()+r;z++){
            Block q=b.getWorld().getBlockAt(x,y,z); if(q.getType()==Material.BUDDING_AMETHYST){double d=q.getLocation().distanceSquared(b.getLocation()); if(d<bd){bd=d;best=q;}}
        }
        return b.getWorld().getUID()+":"+Math.floorDiv(best.getX(),16)+":"+best.getY()+":"+Math.floorDiv(best.getZ(),16);
    }
    private void trySpawnSpecial(Block core){
        if(ThreadLocalRandom.current().nextDouble()>getConfig().getDouble("special-amethyst-geode-chance",0.5))return;
        List<Block> candidates=new ArrayList<>(); int r=8;
        for(int x=core.getX()-r;x<=core.getX()+r;x++) for(int y=Math.max(core.getWorld().getMinHeight(),core.getY()-r);y<=Math.min(core.getWorld().getMaxHeight()-1,core.getY()+r);y++) for(int z=core.getZ()-r;z<=core.getZ()+r;z++){
            Block b=core.getWorld().getBlockAt(x,y,z); Material m=b.getType();
            if(m==Material.AMETHYST_CLUSTER||m==Material.LARGE_AMETHYST_BUD||m==Material.MEDIUM_AMETHYST_BUD||m==Material.SMALL_AMETHYST_BUD)candidates.add(b);
        }
        if(candidates.isEmpty())return;
        Block target=candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        target.setType(Material.AMETHYST_CLUSTER,false);
        markSpecial(target);
    }
    private void markSpecial(Block b){
        String k=key(b); specialDisplays.remove(k); b.setMetadata("crystal_amethyst_special",new org.bukkit.metadata.FixedMetadataValue(this,true));
        BlockDisplay d=b.getWorld().spawn(b.getLocation().add(0.5,0.5,0.5),BlockDisplay.class,ent->{
            BlockData data=Bukkit.createBlockData(Material.AMETHYST_CLUSTER); AmethystCluster a=(AmethystCluster)data; a.setFacing(org.bukkit.block.BlockFace.UP); a.setWaterlogged(false); a.setStage(3); ent.setBlock(data); ent.setTransformation(new Transformation(new Vector3f(-0.175f,-0.175f,-0.175f),new org.joml.Quaternionf(),new Vector3f((float)getConfig().getDouble("special-amethyst-display-scale",1.35),(float)getConfig().getDouble("special-amethyst-display-scale",1.35),(float)getConfig().getDouble("special-amethyst-display-scale",1.35)),new org.joml.Quaternionf())); ent.setGlowing(true); ent.setPersistent(true);
        }); specialDisplays.put(k,d.getUniqueId());
    }

    @EventHandler(ignoreCancelled=true)
    public void onBreak(BlockBreakEvent e){
        Block b=e.getBlock(); String k=key(b);
        if(growing.containsKey(k)){e.setCancelled(true);e.getPlayer().sendMessage(ChatColor.RED+"Этот аметист ещё растёт.");return;}
        if(b.hasMetadata("crystal_amethyst_special")){e.setDropItems(false); removeDisplay(b); b.removeMetadata("crystal_amethyst_special",this); b.getWorld().dropItemNaturally(b.getLocation(),special()); return;}
    }
    @EventHandler public void onExplode(EntityExplodeEvent e){ e.blockList().removeIf(b->b.hasMetadata("crystal_amethyst_special")); }
    private void removeDisplay(Block b){UUID id=specialDisplays.remove(key(b));if(id!=null){Entity en=Bukkit.getEntity(id);if(en!=null)en.remove();}}

    @EventHandler(ignoreCancelled=true)
    public void onPlace(BlockPlaceEvent e){
        if(!"seed".equals(type(e.getItemInHand())))return;
        Block b=e.getBlockPlaced(); e.setCancelled(true);
        if(!b.getType().isAir())return;
        b.setType(Material.BUDDING_AMETHYST,false);
        long base=getConfig().getLong("growth-seconds",3600)*1000L;
        growing.put(key(b),new Growth(base,0,System.currentTimeMillis()));
        consumeOne(e.getPlayer(),e.getItemInHand()); e.getPlayer().sendMessage(msg("planted"));
    }
    private void tickGrowth(){
        long now=System.currentTimeMillis(); Iterator<Map.Entry<String,Growth>> it=growing.entrySet().iterator();
        while(it.hasNext()){
            var en=it.next(); Block b=findBlock(en.getKey()); if(b==null||b.getType()!=Material.BUDDING_AMETHYST){it.remove();continue;}
            Growth g=en.getValue(); long delta=now-g.lastUpdate(); double mult=growthMultiplier(b); long progress=g.progressMillis()+(long)(delta*mult);
            if(progress>=g.requiredMillis()){
                b.setType(Material.AMETHYST_CLUSTER,false); markSpecial(b); it.remove(); b.getWorld().spawnParticle(Particle.END_ROD,b.getLocation().add(.5,.5,.5),20,.35,.35,.35,.02); b.getWorld().playSound(b.getLocation(),Sound.BLOCK_AMETHYST_BLOCK_CHIME,1,1.2f); 
            } else en.setValue(new Growth(g.requiredMillis(),progress,now));
        }
    }
    private double growthMultiplier(Block b){
        double m=1.0; World w=b.getWorld(); int light=w.getBlockAt(b.getX(),b.getY(),b.getZ()).getLightLevel();
        if(light<=7)m*=getConfig().getDouble("dark-growth-multiplier",1.25);
        long time=w.getTime(); boolean night=time>=13000&&time<23000;
        if(night)m*=getConfig().getDouble("night-growth-multiplier",1.25);
        return Math.min(m,1.5);
    }
    private Block findBlock(String k){String[] p=k.split(":",4); if(p.length!=4)return null; try{return Bukkit.getWorld(UUID.fromString(p[0])).getBlockAt(Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));}catch(Exception x){return null;}}

    @EventHandler(ignoreCancelled=true) public void onInteract(PlayerInteractEvent e){
        if(e.getHand()!=EquipmentSlot.HAND||e.getAction()!=Action.RIGHT_CLICK_AIR&&e.getAction()!=Action.RIGHT_CLICK_BLOCK)return; ItemStack i=e.getItem(); String t=type(i); Player p=e.getPlayer();
        if("amethyst".equals(t)){p.getInventory().setItemInMainHand(seed());consumeOne(p,i);return;}
        if("crystal".equals(t)){if(economy==null){p.sendMessage(msg("no-vault"));return;}double a=ThreadLocalRandom.current().nextInt(getConfig().getInt("crystal-coin-min",1),getConfig().getInt("crystal-coin-max",50)+1);if(economy.depositPlayer(p,a).transactionSuccess()){consumeOne(p,i);p.sendMessage(msg("reward-vault").replace("%amount%",String.valueOf((int)a)));}}
        if("amethystcoin".equals(t)){if(playerPointsApi==null){p.sendMessage(msg("no-points"));return;}int a=ThreadLocalRandom.current().nextInt(getConfig().getInt("amethyst-coin-min",1),getConfig().getInt("amethyst-coin-max",3)+1);try{Method m=playerPointsApi.getClass().getMethod("give",UUID.class,int.class);Object r=m.invoke(playerPointsApi,p.getUniqueId(),a);if(!(r instanceof Boolean)||((Boolean)r)){consumeOne(p,i);p.sendMessage(msg("reward-points").replace("%amount%",String.valueOf(a)));}}catch(Exception x){p.sendMessage(msg("no-points"));}}
    }
    private void consumeOne(Player p,ItemStack i){if(i.getAmount()<=1)p.getInventory().setItemInMainHand(null);else i.setAmount(i.getAmount()-1);}
    private void registerRecipes(){ShapedRecipe c=new ShapedRecipe(new NamespacedKey(this,"crystal_coin"),crystal());c.shape("AAA","AAA","AAA");c.setIngredient('A',new RecipeChoice.ExactChoice(special()));Bukkit.addRecipe(c);ShapedRecipe a=new ShapedRecipe(new NamespacedKey(this,"amethyst_coin"),amCoin());a.shape("CCC","CCC","CCC");a.setIngredient('C',new RecipeChoice.ExactChoice(crystal()));Bukkit.addRecipe(a);}
    private boolean command(CommandSender s,Command c,String l,String[] a){if(a.length>=2&&a[0].equalsIgnoreCase("give")){Player p=Bukkit.getPlayerExact(a[1]);if(p==null){s.sendMessage(ChatColor.RED+"Игрок не найден.");return true;}String t=a.length>=3?a[2].toLowerCase():"seed";int n=a.length>=4?Math.max(1,Integer.parseInt(a[3])):1;ItemStack out=switch(t){case "seed"->seed();case "amethyst"->special();case "crystal"->crystal();case "amethystcoin"->amCoin();default->null;};if(out==null){s.sendMessage(msg("usage"));return true;}out.setAmount(n);p.getInventory().addItem(out);return true;}s.sendMessage(msg("usage"));return true;}
    private String msg(String k){return ChatColor.translateAlternateColorCodes('&',getConfig().getString("messages."+k,k));}
}
