package com.roland.itemdatabase.data;

import com.mojang.blaze3d.platform.NativeImage;
import com.roland.itemdatabase.ItemDatabase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class TextureDownloader {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Map<String, AnimatedTexture> ANIMATED_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING_DOWNLOADS = Collections.synchronizedSet(new HashSet<>());
    private static final Path CACHE_DIR = Paths.get("itemdatabase_cache");

    static {
        try {
            if (!Files.exists(CACHE_DIR)) {
                Files.createDirectories(CACHE_DIR);
            }
        } catch (Exception e) {
            ItemDatabase.LOGGER.error("[ItemDatabase] Failed to create texture cache folder", e);
        }
    }

    public static AnimatedTexture getTextureInfo(String cacheKey) {
        return ANIMATED_CACHE.get(cacheKey);
    }

    public static Identifier getOrLoadTexture(String url, String cacheKey) {
        if (url == null || url.isBlank()) return null;

        AnimatedTexture anim = ANIMATED_CACHE.get(cacheKey);
        if (anim != null) {
            return anim.getCurrentFrameIdentifier();
        }

        if (PENDING_DOWNLOADS.add(cacheKey)) {
            loadOrDownloadAsync(url, cacheKey);
        }

        return null;
    }

    private static void loadOrDownloadAsync(String url, String cacheKey) {
        CompletableFuture.runAsync(() -> {
            boolean isGif = url.toLowerCase().endsWith(".gif");
            String fileName = cacheKey.replaceAll("[^a-zA-Z0-9_-]", "_") + (isGif ? ".gif" : ".png");
            Path localFile = CACHE_DIR.resolve(fileName);

            // 1. Check local disk cache
            if (Files.exists(localFile)) {
                try {
                    byte[] bytes = Files.readAllBytes(localFile);
                    if (bytes.length > 0) {
                        decodeAndRegister(bytes, cacheKey, isGif);
                        return;
                    }
                } catch (Exception e) {
                    ItemDatabase.LOGGER.warn("[ItemDatabase] Failed reading disk cache for {}", cacheKey, e);
                }
            }

            // 2. Fetch from network if not cached locally
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();

            CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            byte[] body = response.body();
                            try {
                                Files.write(localFile, body);
                            } catch (Exception e) {
                                ItemDatabase.LOGGER.warn("[ItemDatabase] Failed writing to disk cache: {}", localFile, e);
                            }
                            decodeAndRegister(body, cacheKey, isGif);
                        } else {
                            ItemDatabase.LOGGER.warn("[ItemDatabase] Failed download: {} status={}", url, response.statusCode());
                            PENDING_DOWNLOADS.remove(cacheKey);
                        }
                    })
                    .exceptionally(ex -> {
                        ItemDatabase.LOGGER.error("[ItemDatabase] Network exception downloading {}", url, ex);
                        PENDING_DOWNLOADS.remove(cacheKey);
                        return null;
                    });
        });
    }

    private static void decodeAndRegister(byte[] bytes, String cacheKey, boolean isGif) {
        try {
            List<BufferedImage> frames = new ArrayList<>();
            List<Integer> delays = new ArrayList<>();
            int canvasWidth = -1;
            int canvasHeight = -1;

            if (isGif) {
                Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                        reader.setInput(stream, false);
                        int numFrames = reader.getNumImages(true);

                        try {
                            IIOMetadata streamMeta = reader.getStreamMetadata();
                            if (streamMeta != null) {
                                Node root = streamMeta.getAsTree(streamMeta.getNativeMetadataFormatName());
                                NodeList children = root.getChildNodes();
                                for (int j = 0; j < children.getLength(); j++) {
                                    Node node = children.item(j);
                                    if ("LogicalScreenDescriptor".equalsIgnoreCase(node.getNodeName())) {
                                        NamedNodeMap attr = node.getAttributes();
                                        Node wNode = attr.getNamedItem("logicalScreenWidth");
                                        Node hNode = attr.getNamedItem("logicalScreenHeight");
                                        if (wNode != null) canvasWidth = Integer.parseInt(wNode.getNodeValue());
                                        if (hNode != null) canvasHeight = Integer.parseInt(hNode.getNodeValue());
                                        break;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}

                        if (canvasWidth <= 0 || canvasHeight <= 0) {
                            canvasWidth = reader.getWidth(0);
                            canvasHeight = reader.getHeight(0);
                        }

                        BufferedImage master = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D masterGraphics = master.createGraphics();

                        for (int i = 0; i < numFrames; i++) {
                            BufferedImage frameImage = reader.read(i);
                            IIOMetadata metadata = reader.getImageMetadata(i);

                            GifFrameMeta frameMeta = getGifFrameMeta(metadata);
                            int delayMs = frameMeta.delayMs;
                            String disposal = frameMeta.disposalMethod;

                            BufferedImage backup = null;
                            if ("restoreToPrevious".equalsIgnoreCase(disposal)) {
                                backup = copyImage(master);
                            }

                            masterGraphics.drawImage(frameImage, frameMeta.x, frameMeta.y, null);

                            frames.add(copyImage(master));
                            delays.add(delayMs);

                            if ("restoreToBackgroundColor".equalsIgnoreCase(disposal)) {
                                masterGraphics.setComposite(java.awt.AlphaComposite.Clear);
                                masterGraphics.fillRect(frameMeta.x, frameMeta.y, frameImage.getWidth(), frameImage.getHeight());
                                masterGraphics.setComposite(java.awt.AlphaComposite.SrcOver);
                            } else if ("restoreToPrevious".equalsIgnoreCase(disposal) && backup != null) {
                                masterGraphics.setComposite(java.awt.AlphaComposite.Src);
                                masterGraphics.drawImage(backup, 0, 0, null);
                                masterGraphics.setComposite(java.awt.AlphaComposite.SrcOver);
                            }
                        }

                        masterGraphics.dispose();
                    } finally {
                        reader.dispose();
                    }
                }
            }

            if (frames.isEmpty()) {
                BufferedImage staticImg = ImageIO.read(new ByteArrayInputStream(bytes));
                if (staticImg != null) {
                    canvasWidth = staticImg.getWidth();
                    canvasHeight = staticImg.getHeight();
                    frames.add(staticImg);
                    delays.add(100);
                }
            }

            if (frames.isEmpty()) {
                PENDING_DOWNLOADS.remove(cacheKey);
                return;
            }

            final int finalWidth = canvasWidth;
            final int finalHeight = canvasHeight;

            Minecraft.getInstance().execute(() -> {
                try {
                    List<Identifier> identifiers = new ArrayList<>();
                    int totalDuration = 0;

                    for (int i = 0; i < frames.size(); i++) {
                        BufferedImage frame = frames.get(i);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(frame, "png", baos);
                        NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(baos.toByteArray()));

                        Identifier id = Identifier.fromNamespaceAndPath("itemdatabase", "dynamic/" + cacheKey.toLowerCase() + "_f" + i);
                        DynamicTexture dynamicTexture = new DynamicTexture(id::toString, nativeImage);
                        Minecraft.getInstance().getTextureManager().register(id, dynamicTexture);

                        identifiers.add(id);
                        totalDuration += delays.get(i);
                    }

                    ANIMATED_CACHE.put(cacheKey, new AnimatedTexture(identifiers, delays, totalDuration, finalWidth, finalHeight));
                } catch (Exception e) {
                    ItemDatabase.LOGGER.error("[ItemDatabase] Failed registering dynamic texture: {}", cacheKey, e);
                } finally {
                    PENDING_DOWNLOADS.remove(cacheKey);
                }
            });

        } catch (Exception e) {
            ItemDatabase.LOGGER.error("[ItemDatabase] Decoding error: {}", cacheKey, e);
            PENDING_DOWNLOADS.remove(cacheKey);
        }
    }

    private static BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    private record GifFrameMeta(int delayMs, String disposalMethod, int x, int y) {}

    private static GifFrameMeta getGifFrameMeta(IIOMetadata metadata) {
        int delayMs = 100;
        String disposalMethod = "none";
        int x = 0;
        int y = 0;

        if (metadata == null) {
            return new GifFrameMeta(delayMs, disposalMethod, x, y);
        }

        try {
            String format = metadata.getNativeMetadataFormatName();
            Node root = metadata.getAsTree(format);
            NodeList children = root.getChildNodes();

            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if ("GraphicControlExtension".equalsIgnoreCase(node.getNodeName())) {
                    NamedNodeMap attr = node.getAttributes();
                    Node delayNode = attr.getNamedItem("delayTime");
                    if (delayNode != null) {
                        int delayHundredths = Integer.parseInt(delayNode.getNodeValue());
                        if (delayHundredths > 0) {
                            delayMs = delayHundredths * 10;
                        }
                    }
                    Node disposalNode = attr.getNamedItem("disposalMethod");
                    if (disposalNode != null) {
                        disposalMethod = disposalNode.getNodeValue();
                    }
                } else if ("ImageDescriptor".equalsIgnoreCase(node.getNodeName())) {
                    NamedNodeMap attr = node.getAttributes();
                    Node leftNode = attr.getNamedItem("imageLeftPosition");
                    Node topNode = attr.getNamedItem("imageTopPosition");
                    if (leftNode != null) x = Integer.parseInt(leftNode.getNodeValue());
                    if (topNode != null) y = Integer.parseInt(topNode.getNodeValue());
                }
            }
        } catch (Exception ignored) {}

        return new GifFrameMeta(Math.max(20, delayMs), disposalMethod, x, y);
    }
}