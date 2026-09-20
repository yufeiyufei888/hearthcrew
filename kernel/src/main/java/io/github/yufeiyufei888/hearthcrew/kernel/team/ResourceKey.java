package io.github.yufeiyufei888.hearthcrew.kernel.team;

import java.util.Objects;
import java.util.UUID;

/**
 * Address of a shared world resource. World id and dimension are part of every
 * key, so an identical block coordinate in another world cannot conflict.
 * Container keys reserve a whole container; a container-slot key conflicts with
 * that whole-container key and with the same slot, but not with another slot.
 */
public record ResourceKey(String worldId, String dimension, Address address) {
    public ResourceKey {
        worldId = nonBlank(worldId, "worldId");
        dimension = nonBlank(dimension, "dimension");
        Objects.requireNonNull(address, "address");
    }

    public sealed interface Address permits BlockAddress, ContainerAddress, ContainerSlotAddress,
            EntityAddress, EntitySlotAddress, ItemQuantityAddress { }

    public record BlockAddress(int x, int y, int z) implements Address { }

    public record ContainerAddress(int x, int y, int z) implements Address { }

    public record ContainerSlotAddress(int x, int y, int z, int slot) implements Address {
        public ContainerSlotAddress {
            if (slot < 0 || slot > 53) throw new IllegalArgumentException("container slot must be 0..53");
        }
    }

    public record EntityAddress(String entityUuid) implements Address {
        public EntityAddress {
            entityUuid = uuid(entityUuid, "entityUuid");
        }
    }

    public record EntitySlotAddress(String entityUuid, int slot) implements Address {
        public EntitySlotAddress {
            entityUuid = uuid(entityUuid, "entityUuid");
            if (slot < 0 || slot > 53) throw new IllegalArgumentException("entity slot must be 0..53");
        }
    }

    /** Quantity pool owned by a body or by the shared team (owner = "team"). */
    public record ItemQuantityAddress(String owner, String itemId) implements Address {
        public ItemQuantityAddress {
            owner = nonBlank(owner, "item owner");
            itemId = nonBlank(itemId, "itemId");
        }
    }

    public static ResourceKey block(String worldId, String dimension, int x, int y, int z) {
        return new ResourceKey(worldId, dimension, new BlockAddress(x, y, z));
    }

    public static ResourceKey container(String worldId, String dimension, int x, int y, int z) {
        return new ResourceKey(worldId, dimension, new ContainerAddress(x, y, z));
    }

    public static ResourceKey containerSlot(String worldId, String dimension, int x, int y, int z, int slot) {
        return new ResourceKey(worldId, dimension, new ContainerSlotAddress(x, y, z, slot));
    }

    public static ResourceKey entity(String worldId, String dimension, String entityUuid) {
        return new ResourceKey(worldId, dimension, new EntityAddress(entityUuid));
    }

    public static ResourceKey entitySlot(String worldId, String dimension, String entityUuid, int slot) {
        return new ResourceKey(worldId, dimension, new EntitySlotAddress(entityUuid, slot));
    }

    public static ResourceKey itemQuantity(String worldId, String dimension, String owner, String itemId) {
        return new ResourceKey(worldId, dimension, new ItemQuantityAddress(owner, itemId));
    }

    public boolean conflicts(ResourceKey other) {
        if (other == null || !worldId.equals(other.worldId) || !dimension.equals(other.dimension)) return false;
        if (address instanceof ItemQuantityAddress left && other.address instanceof ItemQuantityAddress right) {
            return left.equals(right);
        }
        if (address instanceof EntityAddress left && other.address instanceof EntityAddress right) return left.equals(right);
        if (address instanceof EntityAddress left && other.address instanceof EntitySlotAddress right) return left.entityUuid().equals(right.entityUuid());
        if (address instanceof EntitySlotAddress left && other.address instanceof EntityAddress right) return left.entityUuid().equals(right.entityUuid());
        if (address instanceof EntitySlotAddress left && other.address instanceof EntitySlotAddress right) {
            return left.entityUuid().equals(right.entityUuid()) && left.slot() == right.slot();
        }
        if (address instanceof ContainerSlotAddress left && other.address instanceof ContainerSlotAddress right) {
            return left.x() == right.x() && left.y() == right.y() && left.z() == right.z() && left.slot() == right.slot();
        }
        int[] leftBlock = blockCoordinates(address);
        int[] rightBlock = blockCoordinates(other.address);
        if (leftBlock != null && rightBlock != null) return java.util.Arrays.equals(leftBlock, rightBlock);
        return false;
    }

    private static int[] blockCoordinates(Address address) {
        return switch (address) {
            case BlockAddress block -> new int[] { block.x(), block.y(), block.z() };
            case ContainerAddress container -> new int[] { container.x(), container.y(), container.z() };
            case ContainerSlotAddress slot -> new int[] { slot.x(), slot.y(), slot.z() };
            default -> null;
        };
    }

    private static String nonBlank(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return value.trim();
    }

    private static String uuid(String value, String label) {
        try { return UUID.fromString(nonBlank(value, label)).toString(); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException(label + " must be a UUID", error); }
    }
}
