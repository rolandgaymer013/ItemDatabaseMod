package com.roland.itemdatabase;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ItemDatabase implements ModInitializer {
	public static final String MOD_ID = "itemdatabase";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[ItemDatabase] Common mod initialized.");
	}
}