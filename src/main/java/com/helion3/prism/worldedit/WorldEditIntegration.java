/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2015 Helion3 http://helion3.com/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.helion3.prism.worldedit;

import com.helion3.prism.util.Translation;

import com.helion3.prism.Prism;
import com.helion3.prism.api.storage.*;
import com.helion3.prism.storage.h2.H2StorageAdapter;
import com.helion3.prism.storage.mysql.MySQLStorageAdapter;
import com.helion3.prism.util.DateUtil;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.command.SendCommandEvent;
import org.spongepowered.api.event.world.LoadWorldEvent;
import org.spongepowered.api.scheduler.Task;

/** Bootstrap has no WE or FAWE linkage, so Prism still works without either mod. */
public final class WorldEditIntegration implements AutoCloseable {
    private final EditJournal journal;
    private final Map<String, String> worlds = new ConcurrentHashMap<>();
    private AutoCloseable hook;
    private boolean worldEditInstalled;
    private boolean bridgeReady;

    public WorldEditIntegration(StorageAdapter storage, Path root) throws Exception {
        Prism prism = Prism.getInstance();
        SqlEditIndex.Connections connections;
        if (storage instanceof MySQLStorageAdapter) {
            connections = MySQLStorageAdapter::getConnection;
        } else if (storage instanceof H2StorageAdapter) {
            connections = H2StorageAdapter::getConnection;
        } else {
            throw new IllegalArgumentException("WE indexes require H2 or MySQL/MariaDB");
        }
        journal =
                new EditJournal(
                        root,
                        new SqlEditIndex(
                                connections,
                                prism.getConfig().getStorageCategory().getTablePrefix()),
                        prism.getConfig().getWorldEditCategory(),
                        prism.getLogger());
        Sponge.getServer()
                .getWorlds()
                .forEach(world -> worlds.put(world.getName(), world.getUniqueId().toString()));
        Sponge.getEventManager().registerListeners(prism.getPluginContainer(), this);
        Task.builder()
                .async()
                .name("PrismWEFileRetention")
                .interval(5, TimeUnit.MINUTES)
                .execute(
                        () -> {
                            if (prism.getConfig().getStorageCategory().isShouldExpire()) {
                                try {
                                    Date before =
                                            DateUtil.parseTimeStringToDate(
                                                    prism.getConfig()
                                                            .getStorageCategory()
                                                            .getExpireRecords(),
                                                    false);
                                    if (before == null) {
                                        throw new IllegalArgumentException(
                                                "Invalid WE retention time");
                                    }
                                    journal.purge(before.getTime());
                                } catch (Exception failure) {
                                    prism.getLogger()
                                            .error(
                                                    "WE archive retention failed; it will be retried",
                                                    failure);
                                }
                            }
                        })
                .submit(prism.getPluginContainer());
        try {
            Class.forName("com.sk89q.worldedit.WorldEdit");
            worldEditInstalled = true;
            String type;
            try {
                Class.forName("com.boydti.fawe.Fawe");
                type = "FaweHook";
            } catch (ClassNotFoundException absent) {
                type = "WorldEditHook";
            }
            Class<?> bridge = Class.forName("com.helion3.prism.worldedit." + type);
            hook =
                    (AutoCloseable)
                            bridge.getConstructor(EditJournal.class, Map.class)
                                    .newInstance(journal, worlds);
            Method register = bridge.getMethod("register");
            register.invoke(hook);
            bridgeReady = true;
            prism.getLogger()
                    .info(
                            "WorldEdit file capture installed using {}. FAWE requires com.helion3.prism in extent.allowed-plugins.",
                            type);
        } catch (ClassNotFoundException absent) {
            if (worldEditInstalled) {
                blockUnavailableBridge(absent);
                return;
            }
            prism.getLogger()
                    .info(
                            "WorldEdit is not installed; existing WE archive queries remain available.");
        } catch (ReflectiveOperationException | LinkageError failure) {
            prism.getLogger().error("WorldEdit capture could not initialize", failure);
            blockUnavailableBridge(failure);
        }
    }

    private void blockUnavailableBridge(Throwable failure) {
        journal.captureFailed(
                new IllegalStateException("WorldEdit bridge initialization failed", failure));
        try {
            Class<?> rejecting =
                    Class.forName("com.helion3.prism.worldedit.RejectingWorldEditHook");
            hook =
                    (AutoCloseable)
                            rejecting
                                    .getConstructor(String.class)
                                    .newInstance(
                                            "recording bridge initialization failed; see console");
            rejecting.getMethod("register").invoke(hook);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            Prism.getInstance()
                    .getLogger()
                    .error(
                            "Cannot install WE rejection hook; disable WorldEdit until the runtime mismatch is fixed",
                            unavailable);
        }
    }

    public StorageAdapter wrap(StorageAdapter storage) {
        StorageAdapterRecords records = new MergedRecords(storage.records(), journal);
        return new StorageAdapter() {
            @Override
            public boolean connect() throws Exception {
                return storage.connect();
            }

            @Override
            public StorageAdapterRecords records() {
                return records;
            }

            @Override
            public StorageAdapterSettings settings() {
                return storage.settings();
            }

            @Override
            public void close() {
                storage.close();
            }

            @Override
            public boolean testConnection() throws Exception {
                return storage.testConnection();
            }
        };
    }

    @Listener
    public void onWorldLoad(LoadWorldEvent event) {
        worlds.put(
                event.getTargetWorld().getName(), event.getTargetWorld().getUniqueId().toString());
    }

    @Listener
    public void onCommand(SendCommandEvent event) {
        String command = event.getCommand().toLowerCase(Locale.ROOT);
        boolean worldEditCommand = command.startsWith("/") || command.startsWith("worldedit:");
        if (!worldEditInstalled || !worldEditCommand) {
            return;
        }
        int namespace = command.indexOf(':');
        if (namespace >= 0) {
            command = command.substring(namespace + 1);
        }
        command = command.replaceFirst("^/+", "");
        // These paths bypass the EditSession block extent or directly modify offline region files.
        if (!bridgeReady
                || command.equals("regen")
                || command.equals("anvil")
                || command.startsWith("anvil.")) {
            event.setCancelled(true);
            event.getCause()
                    .first(org.spongepowered.api.command.CommandSource.class)
                    .ifPresent(
                            source ->
                                    source.sendMessage(
                                            com.helion3.prism.util.Format.error(
                                                    Translation.from("we.unavailable"))));
        }
    }

    @Override
    public void close() throws Exception {
        if (hook != null) {
            hook.close();
        }
        journal.close();
    }
}
