package com.carrot.lethalcarrot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.block.tileentity.TileEntity;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.data.manipulator.mutable.RepresentedPlayerData;
import org.spongepowered.api.data.manipulator.mutable.SkullData;
import org.spongepowered.api.data.persistence.DataFormats;
import org.spongepowered.api.data.type.SkullTypes;
import org.spongepowered.api.effect.particle.ParticleEffect;
import org.spongepowered.api.effect.particle.ParticleTypes;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.EntityTypes;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.entity.spawn.EntitySpawnCause;
import org.spongepowered.api.event.cause.entity.spawn.SpawnTypes;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.ItemStackSnapshot;
import org.spongepowered.api.item.inventory.type.InventoryRow;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.service.pagination.PaginationList;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.channel.MessageChannel;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import com.carrot.lethalcarrot.object.Grave;
import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;
import com.flowpowered.math.vector.Vector3i;
import com.google.common.reflect.TypeToken;

import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;


public class InventorySave {
	private static File configFolder;
	private static File gravesFolder;
	private static ConfigurationNode dbNode;
	private static ConfigurationLoader<CommentedConfigurationNode> loader;

	private static Map<UUID, List<String>> keepinv = new HashMap<>();
	private static HashSet<UUID> inUse = new HashSet<>();
	private static Map<UUID, Map<Vector3i, Grave>> graves = new HashMap<>();

	private static ParticleEffect graveParticle = ParticleEffect.builder().type(ParticleTypes.FIREWORKS_SPARK).quantity(6).build();

	private InventorySave() {
	}

	public static void init(File rootDir) throws IOException {
		configFolder = new File(rootDir, "keepinv");
		gravesFolder = new File(configFolder, "graves");
		gravesFolder.mkdirs();

		File db = new File(configFolder, "db.json");
		db.createNewFile();

		loader = HoconConfigurationLoader.builder().setFile(db).build();
		dbNode = loader.load();
		for (Entry<Object, ? extends ConfigurationNode> hardNode : dbNode.getNode("keepinv").getChildrenMap().entrySet()) {
			try {
				keepinv.put(UUID.fromString(hardNode.getKey().toString()), hardNode.getValue().getList(TypeToken.of(String.class)));
			} catch (ObjectMappingException e) {
				KeepInv.getLogger().error("Unable to get keepinv file list for " + hardNode.getKey().toString());
				e.printStackTrace();
			}		
		}
		for (ConfigurationNode hardNode : dbNode.getNode("graves").getChildrenList()) {
			try {
				Grave grave = hardNode.getValue(TypeToken.of(Grave.class));
				if (!graves.containsKey(grave.world))
					graves.put(grave.world, new HashMap<>());
				graves.get(grave.world).put(grave.location, grave);
			} catch (ObjectMappingException e) {
				e.printStackTrace();
			}
		}
	}

	static private void sync() {
		dbNode.removeChild("keepinv");
		for (UUID uuid : keepinv.keySet()) {
			dbNode.getNode("keepinv").getNode(uuid).setValue(keepinv.get(uuid));
		}

		dbNode.removeChild("graves");
		for (Map<Vector3i, Grave> world : graves.values()) {
			for (Grave grave : world.values())
				try {
					dbNode.getNode("graves").getAppendedNode().setValue(TypeToken.of(Grave.class), grave);
				} catch (ObjectMappingException e) {
					e.printStackTrace();
				}
		}

		try {
			loader.save(dbNode);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	static public Location<World> spawnGrave(Player player) {
		Location<World> loc = findLocation(player.getLocation());
		Sponge.getScheduler()
		.createTaskBuilder()
		.execute(new Runnable() {

			@Override
			public void run() {
				loc.setBlockType(BlockTypes.SKULL, KeepInv.getCause());
				MessageChannel.TO_CONSOLE.send(Text.of("Spawned grave of ", player.getName(), " at ", loc.getBlockX(), " ", loc.getBlockY(), " ", loc.getBlockZ(), " [",loc.getExtent().getName(), "]"));
				player.sendMessage(Text.of(TextColors.DARK_GREEN, "Your grave spawned at ", TextColors.YELLOW, loc.getBlockX(), " ", loc.getBlockY(), " ", loc.getBlockZ(), TextColors.GRAY, " [", loc.getExtent().getName(), "]"));
				Optional<TileEntity> grave = loc.getTileEntity();

				if (grave.isPresent() && grave.get().supports(SkullData.class)) {
					Optional<SkullData> data = grave.get().getOrCreate(SkullData.class);
					if (data.isPresent()) {
						SkullData skullData = data.get();
						skullData.set(Keys.SKULL_TYPE, SkullTypes.PLAYER);
						grave.get().offer(skullData);
					}
					if (grave.get().supports(RepresentedPlayerData.class)) {
						Optional<RepresentedPlayerData> pdata = grave.get().getOrCreate(RepresentedPlayerData.class);
						if (pdata.isPresent()) {
							RepresentedPlayerData playerData = pdata.get();
							playerData.set(Keys.REPRESENTED_PLAYER, player.getProfile());
							grave.get().offer(playerData);
						}
					}
				}

			}
		})
		.submit(KeepInv.getInstance());

		return loc;
	}

	public static void info(Player player, Location<World> loc) {
		if (!graves.containsKey(loc.getExtent().getUniqueId()) || !graves.get(loc.getExtent().getUniqueId()).containsKey(loc.getBlockPosition()))
			return ;
		graves.get(loc.getExtent().getUniqueId()).get(loc.getBlockPosition()).info(player);
	}

	static public boolean isGrave(Location<World> loc) {
		if (!graves.containsKey(loc.getExtent().getUniqueId()))
			return false;
		return graves.get(loc.getExtent().getUniqueId()).containsKey(loc.getBlockPosition());
	}
	
	static public int listGraves(CommandSource src, User player) {
		List<Text> contents = new ArrayList<>();
		for (Entry<UUID, Map<Vector3i, Grave>> entryW : graves.entrySet()) {
			String world = "Unknown";
			if (Sponge.getServer().getWorldProperties(entryW.getKey()).isPresent())
				world = Sponge.getServer().getWorldProperties(entryW.getKey()).get().getWorldName();
			for (Entry<Vector3i, Grave> entryG : entryW.getValue().entrySet()) {
				if (entryG.getValue().owner.equals(player.getUniqueId())) {
					contents.add(Text.of(
							TextColors.GRAY, " [",  world, "] ",
							TextColors.YELLOW, entryG.getKey().getX(), " ", entryG.getKey().getY(), " ", entryG.getKey().getZ(),
							TextColors.GRAY, " - ",
							TextColors.YELLOW, entryG.getValue().date));
				}
			}
		}
		
		PaginationList.builder()
		.title(Text.of(TextColors.GOLD, "{ ", TextColors.GREEN, "Graves of ", TextColors.YELLOW, player.getName() , TextColors.GOLD, " }"))
		.contents(contents)
		.padding(Text.of("-"))
		.sendTo(src);
		return contents.size();
	}

	static public boolean hasItems(Player player) {
		return keepinv.containsKey(player.getUniqueId());
	}

	static private void giveItems(Player player, List<ItemStack> items) {
		for (ItemStack item : items) {
			for (ItemStackSnapshot reject : player.getInventory().query(InventoryRow.class).offer(item).getRejectedItems()){
				Entity newItem = player.getWorld().createEntity(EntityTypes.ITEM, player.getLocation().getPosition());
				newItem.offer(Keys.REPRESENTED_ITEM, reject);
				player.getWorld().spawnEntity(newItem, Cause.source(EntitySpawnCause.builder()
						.entity(newItem).type(SpawnTypes.PLUGIN).build()).build());
			}
		}
	}

	static public boolean load(Player player) throws IOException, ObjectMappingException {
		if (inUse.contains(player.getUniqueId())) {
			player.sendMessage(Text.of(TextColors.DARK_RED, "Wait 5 seconds between two calls"));
			return true;
		}
		if (keepinv.containsKey(player.getUniqueId())) {
			inUse.add(player.getUniqueId());
			for (String file : keepinv.get(player.getUniqueId())) {
				giveItems(player, loadEntities(file));
				keepinv.remove(player.getUniqueId());
			}
			Sponge.getScheduler().createTaskBuilder().execute(new Consumer<Task>() {
				@Override
				public void accept(Task t) {
					t.cancel();
					inUse.remove(player.getUniqueId());
				}
			}).delay(5, TimeUnit.SECONDS).submit(KeepInv.getInstance());
			return true;
		}
		return false;
	}

	static public void load(Player player, String file) throws IOException, ObjectMappingException {
		giveItems(player, loadEntities(file));
	}

	static public void load(Player player, Location<World> loc) throws IOException, ObjectMappingException {
		if (!graves.containsKey(loc.getExtent().getUniqueId()))
			return ;
		if (graves.get(loc.getExtent().getUniqueId()).containsKey(loc.getBlockPosition())) {
			giveItems(player, loadEntities(graves.get(loc.getExtent().getUniqueId()).get(loc.getBlockPosition()).items));
			graves.get(loc.getExtent().getUniqueId()).remove(loc.getBlockPosition());
			if (graves.get(loc.getExtent().getUniqueId()).isEmpty())
				graves.remove(loc.getExtent().getUniqueId());
			loc.setBlockType(BlockTypes.AIR, KeepInv.getCause());
			player.spawnParticles(graveParticle, loc.getPosition(), 60);
		}
	}

	static public void save(Player player, List<Entity> entities) throws IOException, ObjectMappingException {	
		if (entities.isEmpty())
			return ;

		if (player.hasPermission("keepinv.keep")) {
			if (!keepinv.containsKey(player.getUniqueId()))
				keepinv.put(player.getUniqueId(), new ArrayList<>());
			keepinv.get(player.getUniqueId()).add(saveEntities(player, entities));
		} else {
			Grave grave = new Grave(player.getUniqueId(), spawnGrave(player), saveEntities(player, entities));
			if (!graves.containsKey(grave.world))
				graves.put(grave.world, new HashMap<>());
			graves.get(grave.world).put(grave.location, grave);
		}
		sync();
	}

	private static List<ItemStack> loadEntities(String filename) throws IOException, ObjectMappingException {
		File graveFile = new File(gravesFolder, filename);

		if (! graveFile.exists()) {
			return new ArrayList<>();
		}

		List<ItemStack> inv = new ArrayList<>();

		try(BufferedReader br = new BufferedReader(new FileReader(graveFile))) {
			for(String line; (line = br.readLine()) != null; ) {
				Optional<ItemStack> item = ItemStack.builder().build(DataFormats.NBT.readFrom(new ByteArrayInputStream(Base64.getDecoder().decode(line))));
				if (item.isPresent()) {
					inv.add(item.get());
				}
			}
		}

		return inv;

	}

	private static String saveEntities(Player player, List<Entity> entities) throws IOException, ObjectMappingException {
		String filename = player.getUniqueId() + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());

		File graveFile = new File(gravesFolder, filename + ".dat");

		int i = 0;
		while (graveFile.exists()) {
			graveFile = new File(gravesFolder, filename + "-" + ++i + ".dat");
		}

		graveFile.createNewFile();

		try (BufferedWriter br = new BufferedWriter(new FileWriter(graveFile))) {
			for (Entity e : entities) {
				Optional<ItemStackSnapshot> item = e.get(Keys.REPRESENTED_ITEM);
				if (item.isPresent()) {
					ByteArrayOutputStream os = new ByteArrayOutputStream();
					DataFormats.NBT.writeTo(os, item.get().toContainer());
					os.flush();
					br.write(Base64.getEncoder().encodeToString(os.toByteArray()));
					br.newLine();
				}
			}
		}

		MessageChannel.TO_CONSOLE.send(Text.of("Inventory of " + player.getName() + " saved."));
		MessageChannel.TO_CONSOLE.send(Text.of("Restore with /restore " + graveFile.getName()));

		return graveFile.getName();
	}

	private static Location<World> findLocation(Location<World> loc) {
		int oX = loc.getBlockX(), oY = loc.getBlockY(), oZ = loc.getBlockZ();
		int next = 0;

		loc.setPosition(new Vector3d(oX, oY, oZ));

		if (oY < 0 || oY > 255) {
			loc = loc.sub(0, oY, 0);
			oY = loc.getBlockY();
		}

		while (!canPlace(loc)) {
			loc = loc.add(0, 1, 0);
			if (loc.getBlockY() > 255 || loc.getBlockY() > oY + 25) {
				Vector2i n = getNextXZ(next++);
				loc.setPosition(new Vector3d(oX + n.getX(), oY - 10 < 0 ? 0 : oY - 10 , oZ + n.getY()));
			}
		}
		return loc;
	}

	private static boolean canPlace(Location<World> loc) {
		if (loc.getBlockType() == BlockTypes.AIR ||
				loc.getBlockType() == BlockTypes.GRASS ||
				loc.getBlockType() == BlockTypes.WATER ||
				loc.getBlockType() == BlockTypes.FLOWING_WATER ||
				loc.getBlockType() == BlockTypes.LAVA || 
				loc.getBlockType() == BlockTypes.FLOWING_LAVA ||
				loc.getBlockType() == BlockTypes.FIRE ||
				loc.getBlockType() == BlockTypes.WEB ||
				loc.getBlockType() == BlockTypes.TALLGRASS)
			return true;
		return false;
	}

	public static Vector2i getNextXZ(int n) {
		int k = (int) Math.ceil((Math.sqrt(n) - 1) / 2);
		int t = 2 * k + 1;
		int m = (int) Math.pow(t, 2);
		t = t - 1;
		if (n >= m - t)
			return new Vector2i(k-(m-n), -k);

		m = m - t;

		if (n >= m - t)
			return new Vector2i(-k, -k+(m-n));

		m = m - t;

		if (n >= m - t)
			return new Vector2i(-k+(m-n), k);

		return new Vector2i(k, k-(m-n-t));
	}

}
