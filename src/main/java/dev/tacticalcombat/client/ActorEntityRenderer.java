package dev.tacticalcombat.client;

import dev.tacticalcombat.actor.ActorEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.RotationAxis;

/**
 * Draws the body of an Actor whose model is a skin, an item or a block. A skin is the player model wearing the skin
 * of the named player (or a texture id given in the model); an item or block floats at the Actor's feet, turning
 * with it, under the usual name tag.
 */
public final class ActorEntityRenderer extends MobEntityRenderer<ActorEntity, PlayerEntityModel<ActorEntity>> {
	private final EntityRendererFactory.Context context;

	public ActorEntityRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
		this.context = ctx;
	}

	@Override
	public Identifier getTexture(ActorEntity entity) {
		String value = entity.modelValue();
		if (!entity.modelKind().equals("skin")) return DefaultSkinHelper.getSkinTextures(Uuids.getOfflinePlayerUuid("Steve")).texture();
		if (entity.cachedFor == null || !entity.cachedFor.equals(entity.getModel()) || !(entity.cached instanceof Identifier)) {
			entity.cachedFor = entity.getModel();
			entity.cached = resolveSkin(value);
		}
		return (Identifier) entity.cached;
	}

	private static Identifier resolveSkin(String value) {
		if (value.contains(":")) {
			Identifier direct = Identifier.tryParse(value); // a texture of a resource pack
			if (direct != null) return direct;
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.getNetworkHandler() != null) {
			PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(value); // a player who is online
			if (entry != null) return entry.getSkinTextures().texture();
		}
		return DefaultSkinHelper.getSkinTextures(Uuids.getOfflinePlayerUuid(value)).texture();
	}

	/** An item or block Actor has no body to draw, only the prop. */
	@Override
	protected RenderLayer getRenderLayer(ActorEntity entity, boolean showBody, boolean translucent, boolean showOutline) {
		if (!entity.modelKind().equals("skin")) return null;
		return super.getRenderLayer(entity, showBody, translucent, showOutline);
	}

	@Override
	public void render(ActorEntity entity, float yaw, float tickDelta, MatrixStack matrices,
					   VertexConsumerProvider vertexConsumers, int light) {
		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
		String kind = entity.modelKind();
		if (kind.equals("skin")) return;

		if (entity.cachedFor == null || !entity.cachedFor.equals(entity.getModel()) || entity.cached == null
				|| entity.cached instanceof Identifier) {
			entity.cachedFor = entity.getModel();
			Identifier id = Identifier.tryParse(entity.modelValue());
			if (kind.equals("item")) {
				entity.cached = id != null && Registries.ITEM.containsId(id) ? new ItemStack(Registries.ITEM.get(id)) : ItemStack.EMPTY;
			} else {
				entity.cached = id != null && Registries.BLOCK.containsId(id) ? Registries.BLOCK.get(id).getDefaultState() : null;
			}
		}

		matrices.push();
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - yaw));
		if (entity.cached instanceof ItemStack stack && !stack.isEmpty()) {
			matrices.translate(0.0, 0.7, 0.0);
			matrices.scale(1.4f, 1.4f, 1.4f);
			context.getItemRenderer().renderItem(stack, ModelTransformationMode.FIXED, light, OverlayTexture.DEFAULT_UV,
					matrices, vertexConsumers, entity.getWorld(), entity.getId());
		} else if (entity.cached instanceof BlockState state) {
			matrices.translate(-0.5, 0.0, -0.5);
			context.getBlockRenderManager().renderBlockAsEntity(state, matrices, vertexConsumers, light, OverlayTexture.DEFAULT_UV);
		}
		matrices.pop();
	}
}
