package com.redstone.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mojang.math.Quadrant;
import com.mojang.math.Transformation;
import com.redstone.blockentity.AnimatedBlockEntity;
import com.redstone.blockentity.AnimationRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renderer for animated block entities.
 *
 * This renderer applies interpolated animation transforms (rotation, translation, scale)
 * to the block's model using PoseStack, then renders the block model.
 *
 * Smooth animation is achieved by interpolating between previous and current tick
 * values using partialTick.
 */
@Environment(EnvType.CLIENT)
public class AnimatedBlockRenderer implements BlockEntityRenderer<AnimatedBlockEntity, AnimatedBlockRenderState> {
    private static final Logger LOGGER = LoggerFactory.getLogger("AnimatedBlockRenderer");

    public AnimatedBlockRenderer(BlockEntityRendererProvider.Context context) {
        // No special initialization needed
    }

    @Override
    public AnimatedBlockRenderState createRenderState() {
        return new AnimatedBlockRenderState();
    }

    @Override
    public void extractRenderState(
        AnimatedBlockEntity entity,
        AnimatedBlockRenderState state,
        float partialTick,
        Vec3 cameraPos,
        ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay
    ) {
        // Call super to extract base state (position, block state, light coords)
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTick, cameraPos, crumblingOverlay);

        // Fix lighting for animated blocks:
        // When querying light at the block's own position, opaque blocks return 0 because
        // we're "inside" the block. Instead, we query light from all 6 adjacent faces and
        // take the maximum, which gives us the light that would be hitting the block's surface.
        if (entity.getLevel() != null) {
            var level = entity.getLevel();
            var pos = entity.getBlockPos();

            // Query light from all 6 adjacent positions and take the maximum
            int maxLight = 0;
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                var adjacentPos = pos.relative(dir);
                int adjacentLight = net.minecraft.client.renderer.LevelRenderer.getLightColor(level, adjacentPos);
                if (adjacentLight > maxLight) {
                    maxLight = adjacentLight;
                }
            }
            state.lightCoords = maxLight;
        }

        // Extract current animation state
        state.rotationX = entity.getRotationX();
        state.rotationY = entity.getRotationY();
        state.rotationZ = entity.getRotationZ();
        state.translateX = entity.getTranslateX();
        state.translateY = entity.getTranslateY();
        state.translateZ = entity.getTranslateZ();
        state.scaleX = entity.getScaleX();
        state.scaleY = entity.getScaleY();
        state.scaleZ = entity.getScaleZ();
        state.pivotX = entity.getPivotX();
        state.pivotY = entity.getPivotY();
        state.pivotZ = entity.getPivotZ();

        // Extract previous tick state for interpolation
        state.oRotationX = entity.getORotationX();
        state.oRotationY = entity.getORotationY();
        state.oRotationZ = entity.getORotationZ();
        state.oTranslateX = entity.getOTranslateX();
        state.oTranslateY = entity.getOTranslateY();
        state.oTranslateZ = entity.getOTranslateZ();
        state.oScaleX = entity.getOScaleX();
        state.oScaleY = entity.getOScaleY();
        state.oScaleZ = entity.getOScaleZ();

        // Store partial tick for interpolation in submit()
        state.partialTick = partialTick;

        // Store handler ID for potential future use
        state.handlerId = entity.getHandlerId();

        // Store block ID for element animation info lookup
        if (entity.getBlockState() != null) {
            state.blockId = BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString();
        }
    }

    @Override
    public void submit(
        AnimatedBlockRenderState state,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        CameraRenderState cameraRenderState
    ) {
        BlockState blockState = state.blockState;
        if (blockState == null || blockState.isAir()) {
            return;
        }

        // Note: We don't skip based on getRenderShape() here because animated blocks
        // use RenderShape.INVISIBLE to prevent the default block renderer from rendering
        // the static model. We render the model ourselves with animation transforms applied.

        poseStack.pushPose();

        // Check if this block uses per-element animation
        AnimationRegistry.ElementAnimationInfo elementInfo = null;
        if (state.blockId != null) {
            elementInfo = AnimationRegistry.getElementAnimationInfo(state.blockId);
        }

        // For per-element animation, DON'T apply transforms to poseStack here
        // Instead, transforms are applied only to animated elements inside renderBlockModel()
        if (elementInfo == null) {
            // No per-element animation: apply transforms to entire model (legacy behavior)
            float rotX = Mth.lerp(state.partialTick, state.oRotationX, state.rotationX);
            float rotY = Mth.lerp(state.partialTick, state.oRotationY, state.rotationY);
            float rotZ = Mth.lerp(state.partialTick, state.oRotationZ, state.rotationZ);
            float transX = Mth.lerp(state.partialTick, state.oTranslateX, state.translateX);
            float transY = Mth.lerp(state.partialTick, state.oTranslateY, state.translateY);
            float transZ = Mth.lerp(state.partialTick, state.oTranslateZ, state.translateZ);
            float scaleX = Mth.lerp(state.partialTick, state.oScaleX, state.scaleX);
            float scaleY = Mth.lerp(state.partialTick, state.oScaleY, state.scaleY);
            float scaleZ = Mth.lerp(state.partialTick, state.oScaleZ, state.scaleZ);

            // Apply translation first (before rotation/scale)
            poseStack.translate(transX, transY, transZ);

            // Move to pivot point for rotation and scaling
            poseStack.translate(state.pivotX, state.pivotY, state.pivotZ);

            // Apply rotations (Y, X, Z order - standard Euler angles)
            if (rotY != 0) {
                poseStack.mulPose(Axis.YP.rotationDegrees(rotY));
            }
            if (rotX != 0) {
                poseStack.mulPose(Axis.XP.rotationDegrees(rotX));
            }
            if (rotZ != 0) {
                poseStack.mulPose(Axis.ZP.rotationDegrees(rotZ));
            }

            // Apply scale
            if (scaleX != 1 || scaleY != 1 || scaleZ != 1) {
                poseStack.scale(scaleX, scaleY, scaleZ);
            }

            // Move back from pivot point
            poseStack.translate(-state.pivotX, -state.pivotY, -state.pivotZ);
        }
        // For per-element animation: poseStack is NOT transformed here
        // renderBlockModel() will handle applying transforms only to animated parts

        // Render the block model
        renderBlockModel(state, poseStack, submitNodeCollector);

        poseStack.popPose();
    }

    /**
     * Directions used for quad rendering (matches Minecraft's DIRECTIONS array).
     */
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * Render the block model using Minecraft's block rendering system.
     * Supports per-element animation via split models:
     * - Static model ({blockName}.json): rendered without transforms
     * - Animated model ({blockName}_animated.json): rendered with animation transforms
     *
     * Note: Even though our blocks use RenderShape.INVISIBLE (to prevent double-rendering),
     * the model is still loaded and available via BlockModelShaper. RenderShape only affects
     * the default rendering pipeline, not model loading.
     */
    private void renderBlockModel(
        AnimatedBlockRenderState state,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        BlockState blockState = state.blockState;

        // Get the block's model directly from BlockModelShaper (this is the static model)
        BlockStateModel staticModel = blockRenderer.getBlockModelShaper().getBlockModel(blockState);
        if (staticModel == null) {
            LOGGER.warn("No model found for animated block state: {}", blockState);
            return;
        }

        // Get block tint color (for things like grass that use biome colors)
        int color = minecraft.getBlockColors().getColor(blockState, null, null, 0);
        float r = (color >> 16 & 0xFF) / 255.0f;
        float g = (color >> 8 & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        // Get the render type for this block
        var renderType = ItemBlockRenderTypes.getRenderType(blockState);

        // Calculate interpolated animation values
        final float finalRotX = Mth.lerp(state.partialTick, state.oRotationX, state.rotationX);
        final float finalRotY = Mth.lerp(state.partialTick, state.oRotationY, state.rotationY);
        final float finalRotZ = Mth.lerp(state.partialTick, state.oRotationZ, state.rotationZ);
        final float finalTransX = Mth.lerp(state.partialTick, state.oTranslateX, state.translateX);
        final float finalTransY = Mth.lerp(state.partialTick, state.oTranslateY, state.translateY);
        final float finalTransZ = Mth.lerp(state.partialTick, state.oTranslateZ, state.translateZ);
        final float finalScaleX = Mth.lerp(state.partialTick, state.oScaleX, state.scaleX);
        final float finalScaleY = Mth.lerp(state.partialTick, state.oScaleY, state.scaleY);
        final float finalScaleZ = Mth.lerp(state.partialTick, state.oScaleZ, state.scaleZ);
        final float pivX = state.pivotX;
        final float pivY = state.pivotY;
        final float pivZ = state.pivotZ;

        // Collect static model parts
        List<BlockModelPart> staticParts = new ArrayList<>();
        staticModel.collectParts(RandomSource.create(42L), staticParts);

        // Check if we have per-element animation info for this block (i.e., split models exist)
        AnimationRegistry.ElementAnimationInfo elementInfo = null;
        if (state.blockId != null) {
            elementInfo = AnimationRegistry.getElementAnimationInfo(state.blockId);
        }

        // If no element info, use the simple path (all elements animated together)
        if (elementInfo == null) {
            // All elements get animation transforms (original behavior)
            submitNodeCollector.submitCustomGeometry(
                poseStack,
                renderType,
                (pose, vertexConsumer) -> {
                    applyAnimationTransforms(pose, finalTransX, finalTransY, finalTransZ,
                        finalRotX, finalRotY, finalRotZ, finalScaleX, finalScaleY, finalScaleZ,
                        pivX, pivY, pivZ);
                    renderAllParts(pose, vertexConsumer, staticParts, r, g, b,
                        state.lightCoords, OverlayTexture.NO_OVERLAY);
                }
            );
        } else {
            // Per-element animation: use split models
            // The static model ({blockName}.json) contains only static elements
            // The animated model ({blockName}_animated.json) contains only animated elements

            // First pass: render static model (no transforms)
            submitNodeCollector.submitCustomGeometry(
                poseStack,
                renderType,
                (pose, vertexConsumer) -> {
                    renderAllParts(pose, vertexConsumer, staticParts, r, g, b,
                        state.lightCoords, OverlayTexture.NO_OVERLAY);
                }
            );

            // Try to get or bake the animated model from JSON
            BakedAnimatedModel animatedModel = getOrBakeAnimatedModel(state.blockId);
            if (animatedModel != null) {
                // Second pass: render animated model (with transforms)
                submitNodeCollector.submitCustomGeometry(
                    poseStack,
                    renderType,
                    (pose, vertexConsumer) -> {
                        applyAnimationTransforms(pose, finalTransX, finalTransY, finalTransZ,
                            finalRotX, finalRotY, finalRotZ, finalScaleX, finalScaleY, finalScaleZ,
                            pivX, pivY, pivZ);
                        renderBakedAnimatedModel(pose, vertexConsumer, animatedModel, r, g, b,
                            state.lightCoords, OverlayTexture.NO_OVERLAY);
                    }
                );
            }
        }
    }

    /**
     * Render a baked animated model.
     */
    private void renderBakedAnimatedModel(
        PoseStack.Pose pose, VertexConsumer vertexConsumer,
        BakedAnimatedModel model,
        float r, float g, float b, int light, int overlay
    ) {
        // Render culled quads for each direction
        for (Direction direction : DIRECTIONS) {
            renderQuadList(pose, vertexConsumer, model.getQuads(direction), r, g, b, light, overlay);
        }
        // Render unculled quads
        renderQuadList(pose, vertexConsumer, model.getQuads(null), r, g, b, light, overlay);
    }

    /**
     * Cache for baked animated quads per block ID.
     * The BakedAnimatedModel contains all quads organized by direction.
     */
    private static final Map<String, BakedAnimatedModel> bakedAnimatedModelCache = new ConcurrentHashMap<>();

    /**
     * Record to store baked quads for animated elements.
     * Similar to QuadCollection but simpler for our use case.
     */
    private record BakedAnimatedModel(
        List<BakedQuad> unculledQuads,
        Map<Direction, List<BakedQuad>> culledQuads,
        TextureAtlasSprite particleIcon
    ) {
        public List<BakedQuad> getQuads(@Nullable Direction direction) {
            if (direction == null) {
                return unculledQuads;
            }
            return culledQuads.getOrDefault(direction, List.of());
        }
    }

    /**
     * Get or bake the animated model for a block.
     *
     * This parses the animated elements JSON stored in AnimationRegistry,
     * bakes quads at runtime using FaceBakery, and caches the result.
     *
     * @param blockId The full block ID (namespace:path)
     * @return The baked animated model, or null if no animated elements JSON is registered
     */
    @Nullable
    private BakedAnimatedModel getOrBakeAnimatedModel(String blockId) {
        // Check cache first
        if (bakedAnimatedModelCache.containsKey(blockId)) {
            return bakedAnimatedModelCache.get(blockId);
        }

        // Get the animated elements JSON from AnimationRegistry
        String modelJson = AnimationRegistry.getAnimatedElementsJson(blockId);
        if (modelJson == null) {
            LOGGER.debug("No animated elements JSON registered for: {}", blockId);
            bakedAnimatedModelCache.put(blockId, null);
            return null;
        }

        try {
            BakedAnimatedModel bakedModel = bakeAnimatedModel(blockId, modelJson);
            bakedAnimatedModelCache.put(blockId, bakedModel);
            LOGGER.debug("Successfully baked animated model for: {} with {} unculled quads",
                blockId, bakedModel.unculledQuads.size());
            return bakedModel;
        } catch (Exception e) {
            LOGGER.error("Failed to bake animated model for {}: {}", blockId, e.getMessage(), e);
            bakedAnimatedModelCache.put(blockId, null);
            return null;
        }
    }

    /**
     * Bake the animated model from JSON.
     *
     * @param blockId The block ID (for logging/debugging)
     * @param modelJson The model JSON containing textures and elements
     * @return The baked animated model with all quads
     */
    private BakedAnimatedModel bakeAnimatedModel(String blockId, String modelJson) {
        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlas blockAtlas = (TextureAtlas) minecraft.getTextureManager()
            .getTexture(TextureAtlas.LOCATION_BLOCKS);

        // Parse the JSON
        JsonObject json = GsonHelper.parse(modelJson);

        // Parse textures map - map texture variable names (e.g., "chest") to texture identifiers
        Map<String, Identifier> textureMap = new HashMap<>();
        if (json.has("textures")) {
            JsonObject texturesObj = json.getAsJsonObject("textures");
            for (var entry : texturesObj.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue().getAsString();
                // Handle texture references (starting with #)
                if (value.startsWith("#")) {
                    // This is a reference to another texture variable - we'll resolve it later
                    continue;
                }
                // Add "block/" prefix if not present (textures are stored with this prefix in the atlas)
                if (!value.contains(":")) {
                    value = "minecraft:" + value;
                }
                Identifier textureId = Identifier.parse(value);
                // In the atlas, block textures are stored without the "block/" prefix in the path
                // but the Identifier should point to the texture location
                textureMap.put(key, textureId);
            }
        }

        // Parse elements
        List<BlockElement> elements = parseElements(json);
        if (elements.isEmpty()) {
            throw new IllegalArgumentException("No elements found in animated model JSON");
        }

        // Bake quads from elements
        List<BakedQuad> unculledQuads = new ArrayList<>();
        Map<Direction, List<BakedQuad>> culledQuads = new HashMap<>();
        for (Direction dir : Direction.values()) {
            culledQuads.put(dir, new ArrayList<>());
        }

        // Create a simple ModelState (identity transform)
        ModelState modelState = new ModelState() {
            @Override
            public Transformation transformation() {
                return Transformation.identity();
            }
        };

        // Create a PartCache for vertex deduplication
        ModelBaker.PartCache partCache = new SimplePartCache();

        // Get particle icon from the first texture in the map
        TextureAtlasSprite particleSprite = blockAtlas.missingSprite();
        if (!textureMap.isEmpty()) {
            Identifier firstTexture = textureMap.values().iterator().next();
            particleSprite = blockAtlas.getSprite(firstTexture);
        }

        // Bake each element
        for (BlockElement element : elements) {
            Vector3fc from = element.from();
            Vector3fc to = element.to();

            // Check for degenerate elements (zero-volume)
            boolean hasX = from.x() != to.x();
            boolean hasY = from.y() != to.y();
            boolean hasZ = from.z() != to.z();
            if (!hasX && !hasY && !hasZ) {
                continue; // Skip degenerate element
            }

            for (var faceEntry : element.faces().entrySet()) {
                Direction direction = faceEntry.getKey();
                BlockElementFace face = faceEntry.getValue();

                // Check if this face should be rendered based on element dimensions
                boolean shouldRender = switch (direction.getAxis()) {
                    case X -> hasY || hasZ;
                    case Y -> hasX || hasZ;
                    case Z -> hasX || hasY;
                };
                if (!shouldRender) {
                    continue;
                }

                // Resolve texture for this face
                String textureRef = face.texture();
                if (textureRef.startsWith("#")) {
                    textureRef = textureRef.substring(1);
                }
                Identifier textureId = textureMap.get(textureRef);
                if (textureId == null) {
                    // Try to resolve as direct texture ID
                    textureId = Identifier.parse(face.texture().startsWith("#") ? "minecraft:missingno" : face.texture());
                }

                TextureAtlasSprite sprite = blockAtlas.getSprite(textureId);

                // Bake the quad
                BakedQuad quad = FaceBakery.bakeQuad(
                    partCache,
                    from,
                    to,
                    face,
                    sprite,
                    direction,
                    modelState,
                    element.rotation(),
                    element.shade(),
                    element.lightEmission()
                );

                // Add to appropriate list based on cullface
                Direction cullDirection = face.cullForDirection();
                if (cullDirection == null) {
                    unculledQuads.add(quad);
                } else {
                    culledQuads.get(cullDirection).add(quad);
                }
            }
        }

        return new BakedAnimatedModel(unculledQuads, culledQuads, particleSprite);
    }

    /**
     * Parse BlockElement list from JSON manually.
     * We can't use the Minecraft Deserializers because they're protected.
     */
    private List<BlockElement> parseElements(JsonObject json) {
        List<BlockElement> elements = new ArrayList<>();

        if (!json.has("elements")) {
            return elements;
        }

        JsonArray elementsArray = json.getAsJsonArray("elements");

        for (int i = 0; i < elementsArray.size(); i++) {
            try {
                JsonObject elemObj = elementsArray.get(i).getAsJsonObject();
                BlockElement element = parseBlockElement(elemObj);
                elements.add(element);
            } catch (Exception e) {
                LOGGER.warn("Failed to parse element {}: {}", i, e.getMessage());
            }
        }

        return elements;
    }

    /**
     * Parse a single BlockElement from JSON.
     */
    private BlockElement parseBlockElement(JsonObject elemObj) {
        // Parse "from" and "to" coordinates
        Vector3f from = parseVector3f(elemObj, "from");
        Vector3f to = parseVector3f(elemObj, "to");

        // Parse faces
        Map<Direction, BlockElementFace> faces = new HashMap<>();
        if (elemObj.has("faces")) {
            JsonObject facesObj = elemObj.getAsJsonObject("faces");
            for (var entry : facesObj.entrySet()) {
                Direction direction = Direction.byName(entry.getKey());
                if (direction != null) {
                    BlockElementFace face = parseBlockElementFace(entry.getValue().getAsJsonObject());
                    faces.put(direction, face);
                }
            }
        }

        // Parse shade (default true)
        boolean shade = GsonHelper.getAsBoolean(elemObj, "shade", true);

        // Parse light emission (default 0)
        int lightEmission = GsonHelper.getAsInt(elemObj, "light_emission", 0);

        // For now, we don't parse rotation - return null for it
        // (element rotation is rarely used for animated blocks)
        return new BlockElement(from, to, faces, null, shade, lightEmission);
    }

    /**
     * Parse a BlockElementFace from JSON.
     */
    private BlockElementFace parseBlockElementFace(JsonObject faceObj) {
        // Parse cullface (can be null)
        Direction cullForDirection = null;
        if (faceObj.has("cullface")) {
            String cullStr = GsonHelper.getAsString(faceObj, "cullface", "");
            cullForDirection = Direction.byName(cullStr);
        }

        // Parse tintindex (default -1 means no tint)
        int tintIndex = GsonHelper.getAsInt(faceObj, "tintindex", -1);

        // Parse texture reference
        String texture = GsonHelper.getAsString(faceObj, "texture");

        // Parse UV coordinates (can be null, will use default based on element bounds)
        BlockElementFace.UVs uvs = null;
        if (faceObj.has("uv")) {
            JsonArray uvArray = GsonHelper.getAsJsonArray(faceObj, "uv");
            if (uvArray.size() == 4) {
                float minU = uvArray.get(0).getAsFloat();
                float minV = uvArray.get(1).getAsFloat();
                float maxU = uvArray.get(2).getAsFloat();
                float maxV = uvArray.get(3).getAsFloat();
                uvs = new BlockElementFace.UVs(minU, minV, maxU, maxV);
            }
        }

        // Parse rotation (default 0 degrees)
        int rotationDegrees = GsonHelper.getAsInt(faceObj, "rotation", 0);
        Quadrant rotation = Quadrant.parseJson(rotationDegrees);

        return new BlockElementFace(cullForDirection, tintIndex, texture, uvs, rotation);
    }

    /**
     * Parse a Vector3f from JSON array.
     */
    private Vector3f parseVector3f(JsonObject obj, String key) {
        JsonArray array = GsonHelper.getAsJsonArray(obj, key);
        if (array.size() != 3) {
            throw new IllegalArgumentException("Expected 3 values for " + key);
        }
        float x = array.get(0).getAsFloat();
        float y = array.get(1).getAsFloat();
        float z = array.get(2).getAsFloat();
        return new Vector3f(x, y, z);
    }

    /**
     * Simple implementation of PartCache for vertex deduplication.
     */
    private static class SimplePartCache implements ModelBaker.PartCache {
        private final Map<Vector3fc, Vector3fc> vectorCache = new HashMap<>();

        @Override
        public Vector3fc vector(Vector3fc vec) {
            return vectorCache.computeIfAbsent(vec, v -> new Vector3f(v));
        }
    }

    /**
     * Clear the baked model cache.
     * Should be called when resources are reloaded.
     */
    public static void clearCache() {
        bakedAnimatedModelCache.clear();
        LOGGER.info("Cleared animated model cache");
    }

    /**
     * Apply animation transforms to a pose.
     */
    private void applyAnimationTransforms(
        PoseStack.Pose pose,
        float transX, float transY, float transZ,
        float rotX, float rotY, float rotZ,
        float scaleX, float scaleY, float scaleZ,
        float pivX, float pivY, float pivZ
    ) {
        var poseMatrix = pose.pose();
        var normalMatrix = pose.normal();

        // Apply translation
        poseMatrix.translate(transX, transY, transZ);

        // Move to pivot point
        poseMatrix.translate(pivX, pivY, pivZ);

        // Apply rotations using Quaternions
        if (rotY != 0) {
            poseMatrix.rotate(Axis.YP.rotationDegrees(rotY));
            normalMatrix.rotate(Axis.YP.rotationDegrees(rotY));
        }
        if (rotX != 0) {
            poseMatrix.rotate(Axis.XP.rotationDegrees(rotX));
            normalMatrix.rotate(Axis.XP.rotationDegrees(rotX));
        }
        if (rotZ != 0) {
            poseMatrix.rotate(Axis.ZP.rotationDegrees(rotZ));
            normalMatrix.rotate(Axis.ZP.rotationDegrees(rotZ));
        }

        // Apply scale
        if (scaleX != 1 || scaleY != 1 || scaleZ != 1) {
            poseMatrix.scale(scaleX, scaleY, scaleZ);
        }

        // Move back from pivot
        poseMatrix.translate(-pivX, -pivY, -pivZ);
    }

    /**
     * Render all model parts (used when no per-element animation is configured).
     */
    private void renderAllParts(
        PoseStack.Pose pose, VertexConsumer vertexConsumer,
        List<BlockModelPart> parts,
        float r, float g, float b, int light, int overlay
    ) {
        for (BlockModelPart part : parts) {
            renderPart(pose, vertexConsumer, part, r, g, b, light, overlay);
        }
    }

    /**
     * Render a single model part.
     * Matches ModelBlockRenderer.renderModel() logic but for a single part.
     */
    private void renderPart(
        PoseStack.Pose pose, VertexConsumer vertexConsumer,
        BlockModelPart part,
        float r, float g, float b, int light, int overlay
    ) {
        // Render quads for each direction (face culled quads)
        for (Direction direction : DIRECTIONS) {
            renderQuadList(pose, vertexConsumer, part.getQuads(direction), r, g, b, light, overlay);
        }
        // Render non-directional quads (always visible)
        renderQuadList(pose, vertexConsumer, part.getQuads(null), r, g, b, light, overlay);
    }

    /**
     * Render a list of quads.
     * Matches ModelBlockRenderer.renderQuadList() logic.
     */
    private void renderQuadList(
        PoseStack.Pose pose, VertexConsumer vertexConsumer,
        List<BakedQuad> quads,
        float r, float g, float b, int light, int overlay
    ) {
        for (BakedQuad quad : quads) {
            float qr, qg, qb;
            if (quad.isTinted()) {
                qr = Mth.clamp(r, 0.0F, 1.0F);
                qg = Mth.clamp(g, 0.0F, 1.0F);
                qb = Mth.clamp(b, 0.0F, 1.0F);
            } else {
                qr = 1.0F;
                qg = 1.0F;
                qb = 1.0F;
            }
            vertexConsumer.putBulkData(pose, quad, qr, qg, qb, 1.0F, light, overlay);
        }
    }

    @Override
    public int getViewDistance() {
        // Render animated blocks from further away for better visibility
        return 64;
    }

    @Override
    public boolean shouldRenderOffScreen() {
        // Always render even when block is technically off-screen
        // (animations might move the model into view)
        return true;
    }
}
