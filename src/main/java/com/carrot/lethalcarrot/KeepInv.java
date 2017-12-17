package com.carrot.lethalcarrot;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.block.ChangeBlockEvent;
import org.spongepowered.api.event.block.InteractBlockEvent;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.NamedCause;
import org.spongepowered.api.event.cause.entity.spawn.EntitySpawnCause;
import org.spongepowered.api.event.entity.living.humanoid.player.RespawnPlayerEvent;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.item.inventory.DropItemEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import com.google.inject.Inject;

import ninja.leaping.configurate.objectmapping.ObjectMappingException;


@Plugin(id = "spongekeepinv", name = "Sponge Keep Inventory", authors = {"Carrot"})
public class KeepInv {
	@Inject
	private Logger logger;

	@Inject
	@ConfigDir(sharedRoot = true)
	private File defaultConfigDir;

	private static KeepInv plugin;

	@Listener
	public void onInit(GameInitializationEvent event) throws IOException
	{	
		plugin = this;
		InventorySave.init(defaultConfigDir);
	}

	@Listener
	public void onStart(GameStartedServerEvent event) {
		CommandSpec restoreInv = CommandSpec.builder()
				.description(Text.of("Retrieve items after death"))
				.executor(new CommandExecutor() {
					@Override
					public CommandResult execute(CommandSource src, CommandContext args) throws CommandException {
						if (src instanceof Player) {
							try {
								if (!InventorySave.load((Player) src)) {
									src.sendMessage(Text.of(TextColors.DARK_AQUA, "No items to get"));
									return CommandResult.empty();
								}
							} catch (IOException | ObjectMappingException e) {
								src.sendMessage(Text.of(TextColors.DARK_RED, "An error occured when loading your items."));
								e.printStackTrace();
								return CommandResult.empty();
							}
							return CommandResult.success();
						} else {
							src.sendMessage(Text.of(TextColors.DARK_RED, "Must be a player"));
							return CommandResult.empty();
						}
					}
				})
				.build();

		CommandSpec fetchFile = CommandSpec.builder()
				.description(Text.of("Get items from backup file"))
				.permission("keepinv.admin")
				.arguments(GenericArguments.string(Text.of("file")))
				.executor(new CommandExecutor() {
					@Override
					public CommandResult execute(CommandSource src, CommandContext args) throws CommandException {
						if (src instanceof Player) {
							Optional<String> file = args.<String>getOne("file");
							if (!file.isPresent()) {
								src.sendMessage(Text.of(TextColors.DARK_AQUA, "Need the file to get the items from"));
								return CommandResult.empty();	
							}
							try {
								InventorySave.load((Player) src, file.get());
							} catch (IOException | ObjectMappingException e) {
								src.sendMessage(Text.of(TextColors.DARK_RED, "An error occured when loading the items. Are you sure that file exists?"));
								e.printStackTrace();
								return CommandResult.empty();
							}
							return CommandResult.success();
						} else {
							src.sendMessage(Text.of(TextColors.DARK_RED, "Must be a player"));
							return CommandResult.empty();
						}
					}
				})
				.build();

		Sponge.getCommandManager().register(this, restoreInv, "keepinv");
		Sponge.getCommandManager().register(this, fetchFile, "restore");

	}

	@Listener(order = Order.EARLY, beforeModifications = true)
	public void onItemDrops(DropItemEvent.Destruct event) {
		Optional<EntitySpawnCause> source = event.getCause().get(NamedCause.SOURCE, EntitySpawnCause.class);
		if (!source.isPresent())
			return;
		if (source.get().getEntity() instanceof Player) {
			Player player = (Player) source.get().getEntity();
			try {
				InventorySave.save(player, event.getEntities());
				event.setCancelled(true);
			} catch (IOException | ObjectMappingException e) {
				player.sendMessage(Text.of(TextColors.DARK_RED, "An error occured. Your items are on the ground."));
				e.printStackTrace();
			}
		}

	}

	@Listener
	public void onEntitySpawn(RespawnPlayerEvent event) {
		if (event.isDeath()) {
			Player player = event.getTargetEntity();
			if (InventorySave.hasItems(player))
				player.sendMessage(Text.of(TextColors.DARK_GREEN, "Your inventory has been saved. ", getClickableKeepInv()));
		}
	}

	@Listener(order=Order.LATE)
	public void onPlayerJoin(ClientConnectionEvent.Join event)
	{
		if (InventorySave.hasItems(event.getTargetEntity()))
			event.getTargetEntity().sendMessage(Text.of(TextColors.DARK_GREEN, "You have items stored from your last death. ", getClickableKeepInv()));
	}

	@Listener
	public void onPlayerRightClick(InteractBlockEvent.Secondary event, @First Player player)
	{
		Optional<Location<World>> loc = event.getTargetBlock().getLocation();
		if (!loc.isPresent())
			return ;
		if (InventorySave.isGrave(loc.get())) {
			event.setCancelled(true);
			try {
				InventorySave.load(player, loc.get());
			} catch (IOException | ObjectMappingException e) {
				player.sendMessage(Text.of(TextColors.DARK_RED, "An error occured. When reading data form the grave."));
				e.printStackTrace();
			}
		}
	}

	@Listener(order=Order.PRE, beforeModifications = true)
	public void onPlayerLeftClick(InteractBlockEvent.Primary event, @First Player player)
	{
		Optional<Location<World>> loc = event.getTargetBlock().getLocation();
		if (!loc.isPresent())
			return ;
		if (InventorySave.isGrave(loc.get())) {
			InventorySave.info(player, loc.get());
		}
	}

	@Listener(order=Order.FIRST, beforeModifications = true)
	public void onBreaksBlock(ChangeBlockEvent.Break event)
	{
		event
		.getTransactions()
		.stream()
		.forEach(trans -> trans.getOriginal().getLocation().ifPresent(loc -> {
			if (InventorySave.isGrave(loc))
				trans.setValid(false);
		}));
	}

	@Listener(order=Order.FIRST, beforeModifications = true)
	public void onChangeBlock(ChangeBlockEvent.Pre event)
	{
		event
		.getLocations()
		.stream()
		.forEach(loc -> {
			if (InventorySave.isGrave(loc)) {
				event.setCancelled(true);
			}
		});
	}

	@Listener(order=Order.FIRST, beforeModifications = true)
	public void onModifyBlock(ChangeBlockEvent.Modify event, @First Player player)
	{
		event
		.getTransactions()
		.stream()
		.forEach(trans -> trans.getOriginal().getLocation().ifPresent(loc -> {
			if (InventorySave.isGrave(loc))
				trans.setValid(false);
		}));
	}

	public static Cause getCause()
	{
		return Cause.source(Sponge.getPluginManager().fromInstance(getInstance()).get()).build();
	}

	public static KeepInv getInstance()
	{
		return plugin;
	}

	public static Logger getLogger()
	{
		return getInstance().logger;
	}

	private static Text getClickableKeepInv() {
		return Text.builder("[GET ITEMS]").color(TextColors.YELLOW).onClick(TextActions.runCommand("/keepinv")).build();
	}

}

