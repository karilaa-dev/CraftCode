package dev.craftcode.ui;

import dev.craftcode.L10n;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Opens the platform's native "open file" dialog through SDL3 (the windowing library Minecraft 26.3 uses).
 * The dialog is non-blocking: it is started on the render thread and its result is delivered later on the
 * render thread. No AWT/Swing is involved.
 */
final class NativeFilePicker {
    private NativeFilePicker() {}

    /** @param paths chosen files (empty when cancelled); @param error set when the dialog could not be shown */
    record Result(List<Path> paths, @Nullable String error) {}

    /**
     * Must be called on the render thread. {@code onResult} runs exactly once, on the render thread,
     * on a later task (never re-entrantly from this call).
     */
    static void openImages(Consumer<Result> onResult) {
        Minecraft mc = Minecraft.getInstance();
        ByteBuffer name = MemoryUtil.memUTF8(L10n.tr("chat.file.filter_images", "Images (PNG, JPEG, GIF, WebP)"));
        ByteBuffer pattern = MemoryUtil.memUTF8("png;jpg;jpeg;gif;webp");
        SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.calloc(1);
        filters.get(0).set(name, pattern);
        SDL_DialogFileCallback[] callback = new SDL_DialogFileCallback[1];
        boolean[] delivered = new boolean[1];
        Runnable free = () -> {
            if (callback[0] != null) callback[0].free();
            filters.free();
            MemoryUtil.memFree(name);
            MemoryUtil.memFree(pattern);
        };
        // SDL may invoke the callback on another thread (e.g. Linux portals) or from inside its event pump;
        // copy the paths out immediately and hand everything else to a queued render-thread task.
        callback[0] = SDL_DialogFileCallback.create((userdata, filelist, filter) -> {
            Result result;
            if (filelist == MemoryUtil.NULL) {
                String error = SDLError.SDL_GetError();
                result = new Result(List.of(), error == null || error.isBlank() ? L10n.tr("chat.file.dialog_unavailable", "The file dialog is not available.") : error);
            } else {
                List<Path> paths = new ArrayList<>();
                for (int i = 0; i < 64; i++) {
                    long entry = MemoryUtil.memGetAddress(filelist + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE);
                    if (entry == MemoryUtil.NULL) break;
                    try {
                        paths.add(Path.of(MemoryUtil.memUTF8(entry)));
                    } catch (InvalidPathException ignored) {
                        // skip unreadable names; the rest are still attached
                    }
                }
                result = new Result(List.copyOf(paths), null);
            }
            mc.schedule(() -> {
                if (delivered[0]) return;
                delivered[0] = true;
                free.run();
                onResult.accept(result);
            });
        });
        try {
            SDLDialog.SDL_ShowOpenFileDialog(callback[0], MemoryUtil.NULL, mc.getWindow().handle(), filters,
                    (CharSequence) null, true);
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            mc.schedule(() -> {
                if (delivered[0]) return;
                delivered[0] = true;
                free.run();
                onResult.accept(new Result(List.of(), L10n.tr("chat.file.dialog_failed", "Could not open the file dialog: %s", message)));
            });
        }
    }
}
