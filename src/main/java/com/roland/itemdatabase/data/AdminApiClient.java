package com.roland.itemdatabase.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roland.itemdatabase.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client handling asynchronous communication between the Fabric client
 * and privileged REST endpoints on the Flask administration backend.
 * <p>
 * Capabilities include:
 * <ul>
 *   <li>Administrative bearer token verification and permission checks.</li>
 *   <li>CRUD operations for Crate Items and Miscellaneous Items.</li>
 *   <li>Item and container reordering (Crates, Misc Groups, and Armor Sets).</li>
 *   <li>Container lifecycle management (Crates, Misc Groups, and Sets).</li>
 *   <li>Multipart form data icon uploading for static (.png, .jpg) and animated (.gif) textures.</li>
 * </ul>
 */
public class AdminApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemDatabase/AdminApi");
    private static final Gson GSON = new Gson();

    /** Shared HTTP client instance configured with HTTP/1.1 and a 6-second connection timeout. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    /**
     * Response payload wrapper returned by the backend authentication route.
     *
     * @param success     True if the bearer token is valid and granted permission.
     * @param username    The authenticated username associated with the token.
     * @param permissions Numerical permission level granted (90+ required for staff).
     * @param error       Descriptive error message if verification fails.
     */
    public record VerifyResult(boolean success, String username, int permissions, String error) {}

    /**
     * Mutation response wrapper returned by database write, update, and delete endpoints.
     *
     * @param success True if the operation succeeded and was committed to the database.
     * @param itemId  The primary key ID of the created or affected entity (-1 if not applicable).
     * @param message Success feedback message from the server.
     * @param error   Error details or status information returned upon failure.
     */
    public record MutationResult(boolean success, int itemId, String message, String error) {}

    /**
     * Flattened item data transfer object representing a crate item row in the admin view.
     */
    public record CrateItemRow(
            int id,
            String name,
            String rarity,
            int sortOrder,
            String winPercentage,
            String primaryTag,
            String secondaryTag,
            String tertiaryTag,
            String quaternaryTag,
            String quinaryTag,
            String senaryTag,
            String septenaryTag,
            String notes
    ) {}

    /**
     * Data transfer object representing an armor or bonus set collection.
     */
    public record SetRow(
            int id,
            String name,
            String type,
            String description,
            List<Integer> itemIds,
            int order
    ) {}

    /**
     * Validates the configured admin API bearer token against GET /api/v1/admin/verify.
     *
     * @return CompletableFuture resolving to a VerifyResult indicating status and permissions.
     */
    public static CompletableFuture<VerifyResult> verifyToken() {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(
                    new VerifyResult(false, null, 0, "No API token configured in itemdatabase.json")
            );
        }

        String url = config.getCleanApiUrl() + "/api/v1/admin/verify";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Accept", "application/json")
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            String username = json.has("username") ? json.get("username").getAsString() : "Admin";
                            int permissions = json.has("permissions") ? json.get("permissions").getAsInt() : 0;
                            return new VerifyResult(true, username, permissions, null);
                        } else {
                            String errorMsg = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new VerifyResult(false, null, 0, errorMsg);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed to parse verify response", e);
                        return new VerifyResult(false, null, 0, "Failed to parse backend response: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.error("Error communicating with admin API at {}", url, ex);
                    return new VerifyResult(false, null, 0, "Connection failed: " + ex.getMessage());
                });
    }

    /**
     * Fetches all items assigned to a specific crate from GET /api/v1/admin/crate/{crateId}/items.
     * Items are returned in their database ItemOrder sequence.
     *
     * @param crateId Unique integer ID of the crate to inspect.
     * @return CompletableFuture resolving to a list of crate item rows.
     */
    public static CompletableFuture<List<CrateItemRow>> fetchCrateItems(int crateId) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        String url = config.getCleanApiUrl() + "/api/v1/admin/crate/" + crateId + "/items";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Accept", "application/json")
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    List<CrateItemRow> list = new ArrayList<>();
                    if (response.statusCode() == 200) {
                        try {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            if (json.has("items")) {
                                JsonArray array = json.getAsJsonArray("items");
                                for (JsonElement el : array) {
                                    JsonObject it = el.getAsJsonObject();
                                    list.add(new CrateItemRow(
                                            it.has("id") ? it.get("id").getAsInt() : 0,
                                            it.has("name") ? it.get("name").getAsString() : "Unknown",
                                            it.has("rarity") ? it.get("rarity").getAsString() : "Common",
                                            it.has("order") ? it.get("order").getAsInt() : 0,
                                            it.has("win_percentage") ? it.get("win_percentage").getAsString() : "0%",
                                            it.has("primary_tag") ? it.get("primary_tag").getAsString() : "None",
                                            it.has("secondary_tag") ? it.get("secondary_tag").getAsString() : "None",
                                            it.has("tertiary_tag") ? it.get("tertiary_tag").getAsString() : "None",
                                            it.has("quaternary_tag") ? it.get("quaternary_tag").getAsString() : "None",
                                            it.has("quinary_tag") ? it.get("quinary_tag").getAsString() : "None",
                                            it.has("senary_tag") ? it.get("senary_tag").getAsString() : "None",
                                            it.has("septenary_tag") ? it.get("septenary_tag").getAsString() : "None",
                                            it.has("notes") ? it.get("notes").getAsString() : ""
                                    ));
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.error("Failed to parse items for crate {}", crateId, e);
                        }
                    } else {
                        LOGGER.warn("Failed to fetch items for crate {}: HTTP {}", crateId, response.statusCode());
                    }
                    return list;
                })
                .exceptionally(ex -> {
                    LOGGER.error("Error fetching items for crate {}", crateId, ex);
                    return new ArrayList<>();
                });
    }

    /**
     * Submits a newly created item draft payload to POST /api/v1/admin/item.
     * Supports creating either Crate items or Miscellaneous items depending on the payload.
     *
     * @param payload JSON serialized item attributes matching the backend schema.
     * @return CompletableFuture resolving to MutationResult containing the newly generated item ID.
     */
    public static CompletableFuture<MutationResult> createItem(JsonObject payload) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(
                    new MutationResult(false, -1, null, "No API token configured")
            );
        }

        String url = config.getCleanApiUrl() + "/api/v1/admin/item";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 201 && json.has("success") && json.get("success").getAsBoolean()) {
                            int id = json.has("item_id") ? json.get("item_id").getAsInt() : -1;
                            String msg = json.has("message") ? json.get("message").getAsString() : "Item created successfully";
                            return new MutationResult(true, id, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Submits updated item metadata to PATCH /api/v1/admin/item/{itemId}.
     *
     * @param itemId  Unique integer ID of the item being modified.
     * @param payload JSON serialized fields to update on the server.
     * @return CompletableFuture resolving to MutationResult indicating success or failure.
     */
    public static CompletableFuture<MutationResult> updateItem(int itemId, JsonObject payload) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(
                    new MutationResult(false, itemId, null, "No API token configured")
            );
        }

        String url = config.getCleanApiUrl() + "/api/v1/admin/item/" + itemId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            String msg = json.has("message") ? json.get("message").getAsString() : "Item updated successfully";
                            return new MutationResult(true, itemId, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, itemId, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, itemId, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, itemId, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Sends DELETE /api/v1/admin/item/{itemId}?type=crate|misc to delete an item and its stored icon.
     *
     * @param itemId      Unique integer ID of the item to delete.
     * @param isCrateItem True for crate items, false for miscellaneous items.
     * @return CompletableFuture resolving to MutationResult indicating deletion status.
     */
    public static CompletableFuture<MutationResult> deleteItem(int itemId, boolean isCrateItem) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(
                    new MutationResult(false, itemId, null, "No API token configured")
            );
        }

        String typeParam = isCrateItem ? "crate" : "misc";
        String url = config.getCleanApiUrl() + "/api/v1/admin/item/" + itemId + "?type=" + typeParam;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Accept", "application/json")
                .DELETE()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            String msg = json.has("message") ? json.get("message").getAsString() : "Item deleted";
                            return new MutationResult(true, itemId, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, itemId, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, itemId, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, itemId, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Uploads a list of serialized item draft payloads sequentially on a background thread.
     *
     * @param payloads List of serialized JSON item objects.
     * @return CompletableFuture resolving to a list of MutationResult records.
     */
    public static CompletableFuture<List<MutationResult>> uploadBatch(List<JsonObject> payloads) {
        return CompletableFuture.supplyAsync(() -> {
            List<MutationResult> results = new ArrayList<>();
            for (JsonObject payload : payloads) {
                try {
                    MutationResult res = createItem(payload).join();
                    results.add(res);
                } catch (Exception e) {
                    results.add(new MutationResult(false, -1, null, e.getMessage()));
                }
            }
            return results;
        });
    }

    /**
     * Uploads a replacement image file for an item's icon using multipart/form-data.
     * Supports animated GIFs, PNGs, and JPEGs.
     *
     * @param itemId Unique integer ID of the target item.
     * @param file   Local image file to upload.
     * @return CompletableFuture resolving to MutationResult indicating upload status.
     */
    public static CompletableFuture<MutationResult> uploadItemIcon(int itemId, java.io.File file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ModConfig config = ModConfig.get();
                if (!config.hasAdminToken()) {
                    return new MutationResult(false, itemId, null, "No API token configured");
                }

                String boundary = "----ItemDatabaseBoundary" + System.currentTimeMillis();
                byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
                String filename = file.getName();

                // Detect appropriate MIME type based on file extension
                String mimeType = filename.toLowerCase().endsWith(".gif") ? "image/gif" : "image/png";

                // Build multipart/form-data payload in memory
                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                body.write(("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                body.write(("Content-Disposition: form-data; name=\"image\"; filename=\"" + filename + "\"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                body.write(("Content-Type: " + mimeType + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                body.write(fileBytes);
                body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

                String endpoint = config.getCleanApiUrl() + "/api/v1/admin/item/" + itemId + "/icon";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Authorization", "Bearer " + config.apiToken.trim())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return new MutationResult(true, itemId, "Icon updated successfully", null);
                } else {
                    return new MutationResult(false, itemId, null, "HTTP " + response.statusCode() + ": " + response.body());
                }
            } catch (Exception e) {
                return new MutationResult(false, itemId, null, e.getMessage());
            }
        });
    }

    /**
     * Submits an updated item order list to POST /api/v1/admin/crate/{crateId}/reorder.
     *
     * @param crateId    Unique integer ID of the parent crate.
     * @param orderedIds List of item IDs arranged in their desired sequential order.
     * @return CompletableFuture resolving to MutationResult indicating commit status.
     */
    public static CompletableFuture<MutationResult> reorderCrateItems(int crateId, List<Integer> orderedIds) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(
                    new MutationResult(false, -1, null, "No API token configured")
            );
        }

        JsonObject payload = new JsonObject();
        JsonArray array = new JsonArray();
        for (Integer id : orderedIds) {
            array.add(id);
        }
        payload.add("item_ids", array);

        String url = config.getCleanApiUrl() + "/api/v1/admin/crate/" + crateId + "/reorder";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            String msg = json.has("message") ? json.get("message").getAsString() : "Items reordered successfully";
                            return new MutationResult(true, -1, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Submits an updated item order list for a miscellaneous group to POST /api/v1/admin/miscgroup/{groupId}/reorder.
     *
     * @param groupId    Unique integer ID of the parent misc group.
     * @param orderedIds List of item IDs arranged in their desired sequential order.
     * @return CompletableFuture resolving to MutationResult indicating commit status.
     */
    public static CompletableFuture<MutationResult> reorderMiscGroupItems(int groupId, List<Integer> orderedIds) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(
                    new MutationResult(false, -1, null, "No API token configured")
            );
        }

        JsonObject payload = new JsonObject();
        JsonArray array = new JsonArray();
        for (Integer id : orderedIds) {
            array.add(id);
        }
        payload.add("item_ids", array);

        String url = config.getCleanApiUrl() + "/api/v1/admin/miscgroup/" + groupId + "/reorder";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            String msg = json.has("message") ? json.get("message").getAsString() : "Items reordered successfully";
                            return new MutationResult(true, -1, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Submits a reordered list of Misc Group IDs to POST /api/v1/admin/miscgroups/reorder.
     * Updates the GroupOrder column across all misc groups in the database.
     *
     * @param orderedGroupIds List of group IDs arranged in their desired order.
     * @return CompletableFuture resolving to MutationResult indicating commit status.
     */
    public static CompletableFuture<MutationResult> reorderMiscGroups(List<Integer> orderedGroupIds) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(
                    new MutationResult(false, -1, null, "No API token configured")
            );
        }

        JsonObject payload = new JsonObject();
        JsonArray array = new JsonArray();
        for (Integer id : orderedGroupIds) {
            array.add(id);
        }
        payload.add("group_ids", array);

        String url = config.getCleanApiUrl() + "/api/v1/admin/miscgroups/reorder";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            String msg = json.has("message") ? json.get("message").getAsString() : "Groups reordered successfully";
                            return new MutationResult(true, -1, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Submits a reordered list of Crate IDs to POST /api/v1/admin/crates/reorder.
     *
     * @param orderedCrateIds List of crate IDs arranged in their desired sequence.
     * @return CompletableFuture resolving to MutationResult indicating commit status.
     */
    public static CompletableFuture<MutationResult> reorderCrates(List<Integer> orderedCrateIds) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(
                    new MutationResult(false, -1, null, "No API token configured")
            );
        }

        JsonObject payload = new JsonObject();
        JsonArray array = new JsonArray();
        for (Integer id : orderedCrateIds) {
            array.add(id);
        }
        payload.add("crate_ids", array);

        String url = config.getCleanApiUrl() + "/api/v1/admin/crates/reorder";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            String msg = json.has("message") ? json.get("message").getAsString() : "Crates reordered successfully";
                            return new MutationResult(true, -1, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Creates a new miscellaneous group via POST /api/v1/admin/miscgroup.
     *
     * @param groupName   Display name of the group.
     * @param releaseDate Release date formatted string.
     * @param urlTag      Slug identifier for web resources.
     * @param groupType   Categorical type (e.g. "Furniture", "Voucher", "Event", "Unsorted").
     * @return CompletableFuture resolving to MutationResult containing the newly created group ID.
     */
    public static CompletableFuture<MutationResult> createMiscGroup(String groupName, String releaseDate, String urlTag, String groupType) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(new MutationResult(false, -1, null, "No API token configured"));
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("GroupName", groupName);
        payload.addProperty("ReleaseDate", releaseDate);
        payload.addProperty("URLTag", urlTag);
        payload.addProperty("GroupType", groupType);

        String url = config.getCleanApiUrl() + "/api/v1/admin/miscgroup";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 201 && json.has("success") && json.get("success").getAsBoolean()) {
                            int newId = json.has("group_id") ? json.get("group_id").getAsInt() : -1;
                            String msg = json.has("message") ? json.get("message").getAsString() : "Group created";
                            return new MutationResult(true, newId, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Updates an existing miscellaneous group container via PATCH /api/v1/admin/miscgroup/{groupId}.
     *
     * @param groupId     Unique integer ID of the group being updated.
     * @param groupName   Updated display name.
     * @param releaseDate Updated release date string.
     * @param urlTag      Updated URL slug.
     * @param groupType   Updated group category type.
     * @return CompletableFuture resolving to MutationResult indicating update status.
     */
    public static CompletableFuture<MutationResult> updateMiscGroup(int groupId, String groupName, String releaseDate, String urlTag, String groupType) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(new MutationResult(false, -1, null, "No API token configured"));
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("GroupName", groupName);
        payload.addProperty("ReleaseDate", releaseDate);
        payload.addProperty("URLTag", urlTag);
        payload.addProperty("GroupType", groupType);

        String url = config.getCleanApiUrl() + "/api/v1/admin/miscgroup/" + groupId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            String msg = json.has("message") ? json.get("message").getAsString() : "Group updated";
                            return new MutationResult(true, groupId, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Deletes a miscellaneous group via DELETE /api/v1/admin/miscgroup/{groupId}.
     *
     * @param groupId Unique integer ID of the group to remove.
     * @return CompletableFuture resolving to MutationResult indicating deletion status.
     */
    public static CompletableFuture<MutationResult> deleteMiscGroup(int groupId) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(new MutationResult(false, -1, null, "No API token configured"));
        }

        String url = config.getCleanApiUrl() + "/api/v1/admin/miscgroup/" + groupId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Accept", "application/json")
                .DELETE()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            String msg = json.has("message") ? json.get("message").getAsString() : "Group deleted";
                            return new MutationResult(true, groupId, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Creates a new crate container via POST /api/v1/admin/crate.
     *
     * @param crateName   Display title for the crate.
     * @param releaseDate Release date formatted string.
     * @param crateTag    Unique URL tag slug.
     * @param crateType   Category grouping (e.g. "Seasonal", "Side", "Misc").
     * @return CompletableFuture resolving to MutationResult containing the newly created crate ID.
     */
    public static CompletableFuture<MutationResult> createCrate(String crateName, String releaseDate, String crateTag, String crateType) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(new MutationResult(false, -1, null, "No API token configured"));
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("CrateName", crateName);
        payload.addProperty("ReleaseDate", releaseDate);
        payload.addProperty("URLTag", crateTag);
        payload.addProperty("CrateType", crateType);

        String url = config.getCleanApiUrl() + "/api/v1/admin/crate";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 201 && json.has("success") && json.get("success").getAsBoolean()) {
                            int newId = json.has("crate_id") ? json.get("crate_id").getAsInt() : -1;
                            String msg = json.has("message") ? json.get("message").getAsString() : "Crate created";
                            return new MutationResult(true, newId, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Updates an existing crate container via PATCH /api/v1/admin/crate/{crateId}.
     *
     * @param crateId     Unique integer ID of the crate being modified.
     * @param crateName   Updated display name.
     * @param releaseDate Updated release date string.
     * @param crateTag    Updated URL tag slug.
     * @param crateType   Updated category type.
     * @return CompletableFuture resolving to MutationResult indicating update status.
     */
    public static CompletableFuture<MutationResult> updateCrate(int crateId, String crateName, String releaseDate, String crateTag, String crateType) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(new MutationResult(false, -1, null, "No API token configured"));
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("CrateName", crateName);
        payload.addProperty("ReleaseDate", releaseDate);
        payload.addProperty("URLTag", crateTag);
        payload.addProperty("CrateType", crateType);

        String url = config.getCleanApiUrl() + "/api/v1/admin/crate/" + crateId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            String msg = json.has("message") ? json.get("message").getAsString() : "Crate updated";
                            return new MutationResult(true, crateId, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Deletes a crate and cascades deletion to all associated items via DELETE /api/v1/admin/crate/{crateId}.
     *
     * @param crateId Unique integer ID of the crate to delete.
     * @return CompletableFuture resolving to MutationResult indicating deletion status.
     */
    public static CompletableFuture<MutationResult> deleteCrate(int crateId) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(new MutationResult(false, -1, null, "No API token configured"));
        }

        String url = config.getCleanApiUrl() + "/api/v1/admin/crate/" + crateId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Accept", "application/json")
                .DELETE()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            String msg = json.has("message") ? json.get("message").getAsString() : "Crate deleted";
                            return new MutationResult(true, crateId, msg, null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Fetches all registered item and armor sets from GET /api/sets.
     *
     * @return CompletableFuture resolving to a list of SetRow records.
     */
    public static CompletableFuture<List<SetRow>> fetchSets() {
        ModConfig config = ModConfig.get();
        String url = config.getCleanApiUrl() + "/api/sets";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    List<SetRow> list = new ArrayList<>();
                    if (response.statusCode() == 200) {
                        try {
                            JsonArray arr = JsonParser.parseString(response.body()).getAsJsonArray();
                            for (var el : arr) {
                                JsonObject obj = el.getAsJsonObject();
                                int id = obj.get("id").getAsInt();
                                String name = obj.has("Name") && !obj.get("Name").isJsonNull() ? obj.get("Name").getAsString() : "Unknown";
                                String type = obj.has("Type") && !obj.get("Type").isJsonNull() ? obj.get("Type").getAsString() : "Armor Set";
                                String desc = obj.has("SetDescription") && !obj.get("SetDescription").isJsonNull() ? obj.get("SetDescription").getAsString() : "";
                                int order = obj.has("SetOrder") && !obj.get("SetOrder").isJsonNull() ? obj.get("SetOrder").getAsInt() : 0;

                                List<Integer> itemIds = new ArrayList<>();
                                if (obj.has("ItemList") && !obj.get("ItemList").isJsonNull()) {
                                    try {
                                        String raw = obj.get("ItemList").getAsString();
                                        JsonArray itemArr = JsonParser.parseString(raw).getAsJsonArray();
                                        for (var itemEl : itemArr) {
                                            itemIds.add(itemEl.getAsInt());
                                        }
                                    } catch (Exception ignored) {}
                                }
                                list.add(new SetRow(id, name, type, desc, itemIds, order));
                            }
                        } catch (Exception ignored) {}
                    }
                    return list;
                })
                .exceptionally(ex -> new ArrayList<>());
    }

    /**
     * Creates a new armor or item set via POST /api/v1/admin/set.
     *
     * @param name        Unique title of the set.
     * @param type        Category type (e.g. "Armor Set").
     * @param description Lore and bonus perk description text.
     * @param itemIds     List of item IDs belonging to this set.
     * @return CompletableFuture resolving to MutationResult containing the created set ID.
     */
    public static CompletableFuture<MutationResult> createSet(String name, String type, String description, List<Integer> itemIds) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(new MutationResult(false, -1, null, "No API token configured"));
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("Name", name);
        payload.addProperty("Type", type);
        payload.addProperty("SetDescription", description);
        JsonArray arr = new JsonArray();
        itemIds.forEach(arr::add);
        payload.add("ItemList", arr);

        String url = config.getCleanApiUrl() + "/api/v1/admin/set";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 201 && json.has("success") && json.get("success").getAsBoolean()) {
                            int newId = json.has("set_id") ? json.get("set_id").getAsInt() : -1;
                            return new MutationResult(true, newId, json.get("message").getAsString(), null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Updates an existing set record via PATCH /api/v1/admin/set/{setId}.
     *
     * @param setId       Unique integer ID of the set being updated.
     * @param name        Updated set name.
     * @param type        Updated set type.
     * @param description Updated bonus description.
     * @param itemIds     Updated constituent item IDs.
     * @return CompletableFuture resolving to MutationResult indicating update status.
     */
    public static CompletableFuture<MutationResult> updateSet(int setId, String name, String type, String description, List<Integer> itemIds) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(new MutationResult(false, -1, null, "No API token configured"));
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("Name", name);
        payload.addProperty("Type", type);
        payload.addProperty("SetDescription", description);
        JsonArray arr = new JsonArray();
        itemIds.forEach(arr::add);
        payload.add("ItemList", arr);

        String url = config.getCleanApiUrl() + "/api/v1/admin/set/" + setId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            return new MutationResult(true, setId, json.get("message").getAsString(), null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Deletes a set record via DELETE /api/v1/admin/set/{setId}.
     *
     * @param setId Unique integer ID of the set to remove.
     * @return CompletableFuture resolving to MutationResult indicating deletion status.
     */
    public static CompletableFuture<MutationResult> deleteSet(int setId) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(new MutationResult(false, -1, null, "No API token configured"));
        }

        String url = config.getCleanApiUrl() + "/api/v1/admin/set/" + setId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Accept", "application/json")
                .DELETE()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            return new MutationResult(true, setId, json.get("message").getAsString(), null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }

    /**
     * Submits a reordered list of set IDs to POST /api/v1/admin/set/reorder.
     * Updates the SetOrder sequence in the backend database.
     *
     * @param setIds List of set IDs arranged in their desired order.
     * @return CompletableFuture resolving to MutationResult indicating commit status.
     */
    public static CompletableFuture<MutationResult> reorderSets(List<Integer> setIds) {
        ModConfig config = ModConfig.get();
        if (!config.hasAdminToken()) {
            return CompletableFuture.completedFuture(new MutationResult(false, -1, null, "No API token configured"));
        }

        JsonObject payload = new JsonObject();
        JsonArray arr = new JsonArray();
        setIds.forEach(arr::add);
        payload.add("set_ids", arr);

        String url = config.getCleanApiUrl() + "/api/v1/admin/set/reorder";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + config.apiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() == 200 && json.has("success") && json.get("success").getAsBoolean()) {
                            return new MutationResult(true, -1, json.get("message").getAsString(), null);
                        } else {
                            String err = json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
                            return new MutationResult(false, -1, null, err);
                        }
                    } catch (Exception e) {
                        return new MutationResult(false, -1, null, "Parse error: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> new MutationResult(false, -1, null, "Connection failed: " + ex.getMessage()));
    }
}