package de.photon.AACAdditionPro.util.fakeentity;

import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import de.photon.AACAdditionPro.AACAdditionPro;
import de.photon.AACAdditionPro.ServerVersion;
import de.photon.AACAdditionPro.api.killauraentity.Movement;
import de.photon.AACAdditionPro.modules.ModuleType;
import de.photon.AACAdditionPro.util.fakeentity.displayinformation.DisplayInformation;
import de.photon.AACAdditionPro.util.fakeentity.equipment.Equipment;
import de.photon.AACAdditionPro.util.mathematics.Hitbox;
import de.photon.AACAdditionPro.util.packetwrappers.server.WrapperPlayServerEntityEquipment;
import de.photon.AACAdditionPro.util.packetwrappers.server.WrapperPlayServerNamedEntitySpawn;
import de.photon.AACAdditionPro.util.random.RandomUtil;
import de.photon.AACAdditionPro.util.random.RandomizedAction;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;

import java.util.concurrent.ThreadLocalRandom;

public class ClientsidePlayerEntity extends ClientsideHittableLivingEntity
{
    private final boolean visible_in_tablist;
    private final boolean shouldAssignTeam;
    private final boolean shouldSwing;
    private boolean shouldSwap;
    private final boolean onlineProfile;

    @Getter
    private final WrappedGameProfile gameProfile;

    // Ping handling
    private int ping;
    private int pingTask;

    @Getter
    private Team currentTeam;

    // Main ticker for the entity
    private final RandomizedAction jumpAction = new RandomizedAction(30, 80)
    {
        @Override
        public void run()
        {
            jump();
        }
    };

    private final RandomizedAction handSwapAction = new RandomizedAction(40, 65)
    {
        @Override
        public void run()
        {
            // Automatic offhand handling
            equipment.replaceHands();
            // Send the updated Equipment
            equipment.updateEquipment();
        }
    };

    private final RandomizedAction armorSwapAction = new RandomizedAction(200, 200)
    {
        @Override
        public void run()
        {
            // Automatic offhand handling
            equipment.replaceRandomArmorPiece();
            // Send the updated Equipment
            equipment.updateEquipment();
        }
    };

    private final RandomizedAction swingAction = new RandomizedAction(15, 65)
    {
        @Override
        public void run()
        {
            swing();
        }
    };

    private final Equipment equipment;

    public ClientsidePlayerEntity(final Player observedPlayer, final WrappedGameProfile gameProfile, boolean onlineProfile)
    {
        super(observedPlayer, Hitbox.PLAYER, Movement.BASIC_FOLLOW_MOVEMENT);

        // Get skin data and name
        this.gameProfile = gameProfile;
        this.onlineProfile = onlineProfile;

        // EquipmentData
        this.equipment = new Equipment(this, ServerVersion.getClientServerVersion(observedPlayer) != ServerVersion.MC188);

        // Init additional behaviour configs
        visible_in_tablist = AACAdditionPro.getInstance().getConfig().getBoolean(ModuleType.KILLAURA_ENTITY.getConfigString() + ".behaviour.visible_in_tablist") &&
                             // Online profiles do not need scoreboard manipulations.
                             !AACAdditionPro.getInstance().getConfig().getBoolean(ModuleType.KILLAURA_ENTITY.getConfigString() + ".prefer_online_profiles");
        shouldAssignTeam = AACAdditionPro.getInstance().getConfig().getBoolean(ModuleType.KILLAURA_ENTITY.getConfigString() + ".behaviour.team.enabled");
        shouldSwing = AACAdditionPro.getInstance().getConfig().getBoolean(ModuleType.KILLAURA_ENTITY.getConfigString() + ".behaviour.swing.enabled");
        shouldSwap = AACAdditionPro.getInstance().getConfig().getBoolean(ModuleType.KILLAURA_ENTITY.getConfigString() + ".behaviour.swap.enabled");
    }

    @Override
    protected void tick()
    {
        super.tick();

        // Teams + Scoreboard
        if (shouldAssignTeam)
        {
            DisplayInformation.applyTeams(this);
        }

        // Try to look to the target
        this.location.setDirection(this.observedPlayer.getEyeLocation()
                                                      // Add randomization
                                                      .add(RandomUtil.randomBoundaryDouble(-0.15, 0.3),
                                                           RandomUtil.randomBoundaryDouble(-0.15, 0.3),
                                                           RandomUtil.randomBoundaryDouble(-0.15, 0.3))
                                                      .toVector()
                                                      // Subtract the current position.
                                                      .subtract(this.getEyeLocation().toVector()));

        this.headYaw = this.location.getYaw();

        this.move(this.location);

        // Maybe we should switch movement states?
        this.jumpAction.cycle();


        // Swap items if needed
        if (shouldSwap)
        {
            handSwapAction.cycle();
            armorSwapAction.cycle();
        }

        // Swing items if enabled
        if (shouldSwing)
        {
            swingAction.cycle();
        }
    }

    // --------------------------------------------------------------- General -------------------------------------------------------------- //

    /**
     * Gets the name of the entity from its game profile.
     */
    public String getName()
    {
        return this.gameProfile.getName();
    }

    // -------------------------------------------------------------- Movement ------------------------------------------------------------ //

    @Override
    public Location getEyeLocation()
    {
        // this.getLocation() is already a cloned location.
        return this.getLocation().add(0, Hitbox.PLAYER.getHeight(), 0);
    }


    // -------------------------------------------------------------- Simulation ------------------------------------------------------------ //

    /**
     * This changes the Ping of the {@link ClientsidePlayerEntity}.
     * The recursive call is needed to randomize the ping-update
     */
    private void recursiveUpdatePing()
    {
        pingTask = Bukkit.getScheduler().scheduleSyncDelayedTask(AACAdditionPro.getInstance(), () -> {
            if (this.isValid())
            {
                // Fake the ping if the entity is already spawned
                if (this.isSpawned())
                {
                    this.ping = RandomUtil.randomBoundaryInt(21, 4);
                    this.updatePlayerInfo(EnumWrappers.PlayerInfoAction.UPDATE_LATENCY, this.ping);
                }

                recursiveUpdatePing();
            }
        }, (long) RandomUtil.randomBoundaryInt(15, 40));
    }

    @Override
    public void setVisibility(final boolean visible)
    {
        super.setVisibility(visible);

        this.shouldSwap = visible;

        if (!visible)
        {
            WrapperPlayServerEntityEquipment.clearAllSlots(this.entityID, this.observedPlayer);
        }
    }

    // ---------------------------------------------------------------- Teams --------------------------------------------------------------- //

    /**
     * Used to make the {@link ClientsidePlayerEntity} join a new {@link Team}
     * If the {@link ClientsidePlayerEntity} is already in a {@link Team} it will try to leave it first.
     *
     * @param team the new {@link Team} to join.
     */
    public void joinTeam(Team team) throws IllegalStateException
    {
        this.leaveTeam();
        team.addEntry(this.gameProfile.getName());
        this.currentTeam = team;
    }

    /**
     * Used to make the {@link ClientsidePlayerEntity} leave its current {@link Team}
     * If the {@link ClientsidePlayerEntity} is in no team nothing will happen.
     */
    private void leaveTeam() throws IllegalStateException
    {
        if (this.currentTeam != null)
        {
            this.currentTeam.removeEntry(this.gameProfile.getName());
            this.currentTeam = null;
        }
    }

    // ---------------------------------------------------------------- Spawn --------------------------------------------------------------- //

    @Override
    public void spawn(Location location)
    {
        super.spawn(location);
        this.lastLocation = location.clone();
        this.move(location);

        // Add the player in the Tablist via PlayerInfo
        this.updatePlayerInfo(EnumWrappers.PlayerInfoAction.ADD_PLAYER, this.ping);

        // Spawn the entity
        final WrapperPlayServerNamedEntitySpawn spawnEntityWrapper = new WrapperPlayServerNamedEntitySpawn();

        spawnEntityWrapper.setEntityID(this.entityID);
        spawnEntityWrapper.setMetadata(spawnEntityWrapper.builder().setHealthMetadata(20F).setSkinMetadata((byte) 127).asWatcher());
        spawnEntityWrapper.setPosition(location.toVector());
        spawnEntityWrapper.setPlayerUUID(this.gameProfile.getUUID());
        spawnEntityWrapper.setYaw(ThreadLocalRandom.current().nextInt(15));
        spawnEntityWrapper.setPitch(ThreadLocalRandom.current().nextInt(15));

        spawnEntityWrapper.sendPacket(observedPlayer);

        // Debug
        // System.out.println("Sent player spawn of bot " + this.entityID + " for " + observedPlayer.getName() + " @ " + location);

        // Set the team (most common on respawn)
        if (shouldAssignTeam)
        {
            DisplayInformation.applyTeams(this);
        }

        if (this.visible_in_tablist)
        {
            this.recursiveUpdatePing();
        }
        else if (!this.onlineProfile)
        {
            this.updatePlayerInfo(EnumWrappers.PlayerInfoAction.REMOVE_PLAYER, this.ping);
        }

        // Entity equipment + armor
        this.equipment.replaceArmor();
        // Automatic offhand handling
        this.equipment.replaceHands();
        this.equipment.updateEquipment();
    }

    // --------------------------------------------------------------- Despawn -------------------------------------------------------------- //

    @Override
    public void despawn()
    {
        if (isSpawned())
        {
            if (this.visible_in_tablist && !this.onlineProfile)
            {
                this.updatePlayerInfo(EnumWrappers.PlayerInfoAction.REMOVE_PLAYER, 0);
            }
            Bukkit.getScheduler().cancelTask(pingTask);
        }

        super.despawn();
    }

    /**
     * Updates the PlayerInformation of a player (or {@link ClientsidePlayerEntity}).
     * This can be used to update the ping ({@link com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction#UPDATE_LATENCY}) <br>
     * or to add ({@link com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction#ADD_PLAYER}) and
     * remove ({@link com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction#REMOVE_PLAYER}) a player from the tablist
     */
    private void updatePlayerInfo(EnumWrappers.PlayerInfoAction action, int ping)
    {
        DisplayInformation.updatePlayerInformation(action,
                                                   this.gameProfile,
                                                   ping,
                                                   EnumWrappers.NativeGameMode.SURVIVAL,
                                                   null,
                                                   this.observedPlayer);
    }
}
