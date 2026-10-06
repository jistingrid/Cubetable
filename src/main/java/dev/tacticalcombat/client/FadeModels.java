package dev.tacticalcombat.client;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.function.Supplier;

/**
 * Wraps every block model so that blocks in {@link BlockFade}'s set are emitted with the translucent blend
 * mode and a reduced vertex alpha when a chunk section is (re)built. Uses the Fabric Rendering API, so it
 * needs a FRAPI renderer (the built-in Indigo, or Sodium with its FRAPI support).
 */
public final class FadeModels {
	private FadeModels() {}

	public static void register() {
		ModelLoadingPlugin.register(plugin ->
				plugin.modifyModelAfterBake().register(ModelModifier.WRAP_PHASE, (model, context) -> {
					ModelIdentifier top = context.topLevelId();
					// only block state models; item models ("inventory") are left alone
					if (model == null || top == null || "inventory".equals(top.getVariant())) return model;
					return new FadingModel(model);
				}));
	}

	/** Makes the quads of one block translucent. */
	private static final RenderContext.QuadTransform FADE = quad -> {
		Renderer renderer = RendererAccess.INSTANCE.getRenderer();
		if (renderer == null) return true;

		RenderMaterial material = renderer.materialFinder()
				.copyFrom(quad.material())
				.blendMode(BlendMode.TRANSLUCENT)
				.find();
		quad.material(material);

		int alpha = Math.round(BlockFade.ALPHA * 255f) << 24;
		for (int i = 0; i < 4; i++) {
			quad.color(i, alpha | (quad.color(i) & 0x00FFFFFF));
		}
		return true;
	};

	private static final class FadingModel extends ForwardingBakedModel {
		FadingModel(BakedModel wrapped) {
			this.wrapped = wrapped;
		}

		@Override
		public boolean isVanillaAdapter() {
			return false;
		}

		@Override
		public void emitBlockQuads(BlockRenderView blockView, BlockState state, BlockPos pos,
								   Supplier<Random> randomSupplier, RenderContext context) {
			if (!BlockFade.isFaded(pos.asLong())) {
				super.emitBlockQuads(blockView, state, pos, randomSupplier, context);
				return;
			}
			context.pushTransform(FADE);
			super.emitBlockQuads(blockView, state, pos, randomSupplier, context);
			context.popTransform();
		}
	}
}
