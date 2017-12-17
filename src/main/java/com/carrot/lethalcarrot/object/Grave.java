package com.carrot.lethalcarrot.object;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import com.flowpowered.math.vector.Vector3i;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class Grave {
	@Setting
	public UUID owner = null;
	@Setting
	public UUID world = null;
	@Setting
	public Vector3i location = null;
	@Setting
	public String items = null;
	@Setting
	public String date = null;

	public Grave() {}

	public Grave(UUID owner, Location<World> location, String items) {
		this.owner = owner;
		this.world = location.getExtent().getUniqueId();
		this.location = location.getBlockPosition();
		this.items = items;
		this.date = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date());
	}

	public void info(Player player) {
		try {
			GameProfile user = Sponge.getServer().getGameProfileManager().get(owner).get();
			if (user.getName().isPresent()){
				player.sendMessage(Text.of(TextColors.DARK_PURPLE, "Here lies ", TextColors.GOLD, user.getName().get(), TextColors.DARK_PURPLE, " passed away on ", TextColors.YELLOW, date));
				return ;
			}
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
		player.sendMessage(Text.of(TextColors.DARK_PURPLE, "Here lies ", TextColors.GOLD, "UNKNOWN", TextColors.DARK_PURPLE, "... An error occured so I don't know who this is... All I know is that they died on ", TextColors.YELLOW, date));
	}
}
