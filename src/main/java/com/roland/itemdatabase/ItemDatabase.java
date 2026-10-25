package com.roland.itemdatabase;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main common entrypoint for the BlossomCraft Item Database mod.
 * Initializes core mod metadata and sets up shared SLF4J logging.
 */
public class ItemDatabase implements ModInitializer {
	public static final String MOD_ID = "itemdatabase";
	public static final String MOD_NAME = "BlossomCraft Item Database";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[{}] Common initializer started.", MOD_NAME);
	}
}