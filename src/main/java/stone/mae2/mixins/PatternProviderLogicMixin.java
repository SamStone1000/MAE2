package stone.mae2.mixins;

import appeng.api.config.Actionable;
import appeng.api.config.LockCraftingMode;
import appeng.api.config.YesNo;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.helpers.patternprovider.UnlockCraftingEvent;
import appeng.util.ConfigManager;
import appeng.util.inv.AppEngInternalInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import stone.mae2.MAE2;
import stone.mae2.appeng.helpers.patternprovider.PatternProviderTargetCache;
import stone.mae2.parts.PatternP2PTunnelPart;
import stone.mae2.parts.PatternP2PTunnelPart.TunneledPatternProviderTarget;
import stone.mae2.parts.PatternP2PTunnelPart.TunneledPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Overwriting a method is terrible, but hopefully no one else is messing with
 * pattern providers
 */
@Mixin(value = PatternProviderLogic.class, remap = false)
public abstract class PatternProviderLogicMixin {

    @Shadow
    private PatternProviderLogicHost host;
    @Shadow
    private IManagedGridNode mainNode;
    @Shadow
    private IActionSource actionSource;
    @Shadow
    private ConfigManager configManager;

    @Shadow
    private int priority;

    // Pattern storing logic
    @Shadow
    private AppEngInternalInventory patternInventory;
    @Shadow
    private List<IPatternDetails> patterns;
    /**
     * Keeps track of the inputs of all the patterns. When blocking mode is
     * enabled, if any of these is contained in the target, the pattern won't be
     * pushed. Always contains keys with the secondary component dropped.
     */
    @Shadow
    private Set<AEKey> patternInputs;
    // Pattern sending logic
    @Shadow
    private List<GenericStack> sendList;
    @Shadow
    private Direction sendDirection;
    // Stack returning logic
    @Shadow
    private PatternProviderReturnInventory returnInv;

    @Shadow
    private YesNo redstoneState;

    @Nullable
    @Shadow
    private UnlockCraftingEvent unlockEvent;
    @Nullable
    @Shadow
    private GenericStack unlockStack;
    @Shadow
    private int roundRobinIndex;
    private TunneledPos sendPos;
    private PatternProviderTargetCache cache;

    /**
     * AE2's code is just not amenable to changes this radical, so I have to
     * overwrite it to allow multiple pattern targets per side. This is
     * potentially possible with finer grained overwrites (is that even
     * possible?) or asking AE2 to change their code to allow this
     * 
     * @param patternDetails
     * @param inputHolder
     * @author Stone
     * @reason Had to rewrite it to be p2p aware, The original method just isn't
     *         flexible enough to do this with usual mixins
     * @return
     */
    @Overwrite
    public boolean pushPattern(IPatternDetails patternDetails,
        KeyCounter[] inputHolder) {
        if (!sendList.isEmpty() || !this.mainNode.isActive()
            || !this.patterns.contains(patternDetails))
        {
            return false;
        }

        var be = host.getBlockEntity();
        var level = be.getLevel();

        if (getCraftingLockedReason() != LockCraftingMode.NONE)
        {
            return false;
        }

        if (!patternDetails.supportsPushInputsToExternalInventory())
        {
            for (Direction direction : getActiveSides())
            {

                Direction adjBeSide = direction.getOpposite();
                // Main change to allow multiple positions to be checked per
                // side
                List<TunneledPos> positions = getTunneledPositions(
                    be.getBlockPos().relative(direction), level, adjBeSide);
                for (TunneledPos adjPos : positions)
                {
                    BlockEntity adjBe = level.getBlockEntity(adjPos.pos());

                    ICraftingMachine craftingMachine = ICraftingMachine
                        .of(level, adjPos.pos(), adjPos.dir(), adjBe);
                    if (craftingMachine != null
                        && craftingMachine.acceptsPlans())
                    {
                        if (craftingMachine.pushPattern(patternDetails,
                            inputHolder, adjPos.dir()))
                        {
                            onPushPatternSuccess(patternDetails);
                            // edit
                            return true;
                        }
                    }
                }
            }
        } else
        {
            // first gather up every adapter, to round robin out patterns
            List<TunneledPatternProviderTarget> adapters = new ArrayList<>();
            for (Direction direction : getActiveSides())
            {
                findAdapters(be, level, adapters, direction);
            }
            rearrangeRoundRobin(adapters);

            for (TunneledPatternProviderTarget adapter : adapters)
            {
                if (adapter.target() == null)
                {
                    continue;
                }
                if (this.isBlocking() && adapter.target()
                    .containsPatternInput(this.patternInputs))
                {
                    continue;
                }

                if (this.adapterAcceptsAll(adapter.target(), inputHolder))
                {
                    patternDetails.pushInputsToExternalInventory(inputHolder,
                        (what, amount) ->
                        {
                            var inserted = adapter.target().insert(what, amount,
                                Actionable.MODULATE);
                            if (inserted < amount)
                            {
                                this.addToSendList(what, amount - inserted);
                            }
                        });
                    onPushPatternSuccess(patternDetails);
                    this.sendPos = adapter.pos();
                    this.cache = findCache(sendPos);
                    this.sendStacksOut(adapter.target());
                    ++roundRobinIndex;
                    return true;
                }
            }
        }

        // return didSomething;
        return false;

    }

    private void findAdapters(BlockEntity be, Level level,
        List<TunneledPatternProviderTarget> adapters, Direction direction) {
        BlockEntity potentialPart = level
            .getBlockEntity(be.getBlockPos().relative(direction));

        if (potentialPart == null || !(potentialPart instanceof IPartHost))
        {
            // no chance of tunneling
            adapters
                .add(new TunneledPatternProviderTarget(findAdapter(direction),
                    new TunneledPos(be.getBlockPos(), direction)));
        } else
        {
            IPart potentialTunnel = ((IPartHost) potentialPart)
                .getPart(direction.getOpposite());
            if (potentialTunnel != null
                && potentialTunnel instanceof PatternP2PTunnelPart)
            {
                List<TunneledPatternProviderTarget> newTargets = ((PatternP2PTunnelPart) potentialTunnel)
                    .getTargets();
                if (newTargets != null)
                {
                    adapters.addAll(newTargets);
                }
            } else
            {
                // not a pattern p2p tunnel
                adapters.add(
                    new TunneledPatternProviderTarget(findAdapter(direction),
                        new TunneledPos(be.getBlockPos(), direction)));
            }
        }
    }

    /**
     * This code is mainly copied from the orginal sendStackOut() method. Mostly
     * just removed the code related to finding the adapter since it's passed in
     * now.
     * 
     * @param adapter
     * @return
     */
    private boolean sendStacksOut(PatternProviderTarget adapter) {
        if (adapter == null)
        {
            return false;
        }

        for (var it = sendList.listIterator(); it.hasNext();)
        {
            var stack = it.next();
            var what = stack.what();
            long amount = stack.amount();

            long inserted = adapter.insert(what, amount, Actionable.MODULATE);
            if (inserted >= amount)
            {
                it.remove();
                return true;
            } else if (inserted > 0)
            {
                it.set(new GenericStack(what, amount - inserted));
                return true;
            }
        }

        if (sendList.isEmpty())
        {
            sendPos = null;
        }

        return false;
    }

    /**
     * AE2 uses this method to send out ingredients that couldn't fit all at
     * once and have to be put in as space is made. I had to change it to use a
     * cached position found when the initial pattern was pushed. The position
     * already has gone through potential p2p tunnels.
     * 
     * @author Stone
     * @reason This method needs to be aware of the pattern p2p, and the
     *         original isn't flexible enough to allow that
     * @return
     */
    @Overwrite
    private boolean sendStacksOut() {
        if (sendPos == null)
        {
            if (!sendList.isEmpty())
            {
                throw new IllegalStateException(
                    "Invalid pattern provider state, this is a bug.");
            }
            return false;
        }

        boolean didSomething = false;

        if (cache == null)
        {
            cache = findCache(sendPos);
        }
        if (sendStacksOut(cache.find()))
        {
            return true;
        }

        return didSomething;
    }

    private List<TunneledPos> getTunneledPositions(BlockPos pos, Level level,
        Direction adjBeSide) {
        BlockEntity potentialPart = level.getBlockEntity(pos);
        if (potentialPart == null || !(potentialPart instanceof IPartHost))
        {
            // can never tunnel
            return List.of(new TunneledPos(pos, adjBeSide));
        } else
        {
            IPart potentialTunnel = ((IPartHost) potentialPart)
                .getPart(adjBeSide);
            if (potentialTunnel instanceof PatternP2PTunnelPart)
            {
                return ((PatternP2PTunnelPart) potentialTunnel)
                    .getTunneledPositions();
            } else
            {
                // not a pattern p2p tunnel
                return List.of(new TunneledPos(pos, adjBeSide));
            }
        }
    }

    @Nullable
    private PatternProviderTargetCache findCache(TunneledPos pos) {
        var thisBe = host.getBlockEntity();
        return new PatternProviderTargetCache((ServerLevel) thisBe.getLevel(),
            pos.pos(), pos.dir().getOpposite(), actionSource);
    }

    @Shadow
    private PatternProviderTarget findAdapter(Direction side) {
        throw new RuntimeException("HOW, HOW DID YOU LOAD THIS!");
    };

    private static final String SEND_POS_TAG = "sendPos";

    /**
     * Writes the send position to nbt data
     * 
     * Writes the current send position to disk to allow it to persist through
     * unloads. Currently this is why MAE2 can't be removed from a save due to
     * running crafts saving that they're crafting, but not where they're
     * crafting. I don't know if there's a clean way to do it (at least no
     * without incurring costs). Preferably I'd like it to be done without any
     * costs because the solution is just to stop all autocrafts before removing
     * MAE2.
     * 
     * @param tag
     * @param ci
     */
    @Inject(method = "writeToNBT", at = @At("TAIL"))
    private void onWriteToNBT(CompoundTag tag, CallbackInfo ci) {
        if (sendPos != null)
        {
            CompoundTag sendPosTag = new CompoundTag();
            sendPos.writeToNBT(sendPosTag);
            tag.put(SEND_POS_TAG, sendPosTag);
        }
    }

    /**
     * Reads the send pos from the nbt data
     * 
     * Reads the saved data off the disk to reconstruct the pattern provider.
     * Note that this will also migrate old pattern providers from AE2 without
     * MAE2 to prevent crashes.
     * 
     * @param tag
     * @param ci
     */
    @Inject(method = "readFromNBT", at = @At("TAIL"))
    private void onReadFromNBT(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains(SEND_POS_TAG))
        // send pos only exists if MAE2 existed before
        {

            sendPos = TunneledPos.readFromNBT(tag.getCompound(SEND_POS_TAG));
        } else if (sendDirection != null)
        // this provider is old and in an invalid state
        {

            BlockEntity be = host.getBlockEntity();
            BlockPos pos = be.getBlockPos();
            // there should only be one position to send to here
            sendPos = getTunneledPositions(pos.relative(sendDirection),
                be.getLevel(), sendDirection).get(0);
            sendDirection = null; // this'll never be reset otherwise
            MAE2.LOGGER.info(
                "Migrated old pattern provider NBT data to MAE2's at ({}, {}, {})",
                pos.getX(), pos.getY(), pos.getZ());
        }
    }

    @Shadow
    private <T> void rearrangeRoundRobin(List<T> list) {
        // TODO Auto-generated method stub

    }

    @Shadow
    public abstract boolean isBlocking();

    @Shadow
    private boolean adapterAcceptsAll(PatternProviderTarget adapter,
        KeyCounter[] inputHolder) {
        // TODO Auto-generated method stub
        return false;
    }

    @Shadow
    private void addToSendList(AEKey what, long l) {
        // TODO Auto-generated method stub

    }

    @Shadow
    private void onPushPatternSuccess(IPatternDetails patternDetails) {
        // TODO Auto-generated method stub

    }

    @Shadow
    private Set<Direction> getActiveSides() {
        // TODO Auto-generated method stub
        return null;
    }

    @Shadow
    public abstract LockCraftingMode getCraftingLockedReason();
}
