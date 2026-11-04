/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.ai.AIFunctionCall;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.ai.AIFunctionType;
import org.jkiss.dbeaver.model.ai.AIImageAttachment;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.ai.AIMessageType;
import org.jkiss.dbeaver.model.ai.AIVisualToolOutput;
import org.jkiss.dbeaver.model.ai.engine.openai.dto.OAIMessageFactory;
import org.jkiss.dbeaver.model.data.json.JSONUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exercises actual image encoding and OpenAI multimodal DTOs without capturing a desktop or writing files. */
public final class WorkbenchVisualCaptureTest {
    public static void main(@NotNull String[] arguments) throws Exception {
        int[] fit = WorkbenchVisualCapture.fit(3200, 1800);
        check(fit[0] == 1600 && fit[1] == 900, "Capture must downsample within its edge bound");
        expectInvalid(() -> WorkbenchVisualCapture.fit(0, 10));
        expectInvalid(() -> WorkbenchVisualCapture.fit(16000, 16000));
        ImageData image = new ImageData(48, 48, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        check(!WorkbenchVisualCapture.hasVisualDetail(image), "A blank image must not be presented as visual evidence");
        for (int row = 0; row < 48; row++) {
            for (int column = 0; column < 48; column++) {
                image.setPixel(column, row, (column + row) % 2 == 0 ? 0xFFFFFF : 0x154CB0);
            }
        }
        check(WorkbenchVisualCapture.hasVisualDetail(image), "Actual image structure should survive capture checks");
        byte[] encoded = WorkbenchVisualCapture.encode(image);
        check(encoded.length <= AIVisualToolOutput.MAX_BYTES, "Encoded image exceeds output budget");
        AIImageAttachment attachment = AIImageAttachment.fromBytes("fixture.jpg", encoded);
        AIVisualToolOutput visual = new AIVisualToolOutput("Fixture window observation", attachment);
        AIFunctionResult result = new AIFunctionResult(AIFunctionType.INFORMATION, visual);
        var serialize = org.jkiss.dbeaver.model.ai.qm.QMAIChatHistoryMapper.class
            .getDeclaredMethod("toFunctionResultJson", AIFunctionResult.class);
        serialize.setAccessible(true);
        String savedResult = (String) serialize.invoke(null, result);
        String directWrapper = JSONUtils.GSON.toJson(visual);
        check(!directWrapper.contains(attachment.data()), "Wrapper image bytes must be transient even in direct serialization");
        check(!savedResult.contains(attachment.data()) && !savedResult.contains("image/jpeg") && savedResult.length() < 4096,
            "Saved function results must contain no screenshot image or Base64 payload");
        AIMessage function = AIMessage.functionCall(new AIFunctionCall("captureWorkbench", Map.of()), result);
        List<AIMessage> request = new ArrayList<>(List.of(function));
        AIVisualToolOutput.appendToRequest(request, result);
        check(request.size() == 2 && request.get(0) == function, "Image must follow the matching function result");
        AIMessage evidence = request.getLast();
        check(evidence.getRole() == AIMessageType.USER && evidence.getImages().size() == 1,
            "Actual multimodal image must use the existing supported image input path");
        String wire = JSONUtils.GSON.toJson(OAIMessageFactory.fromAIMessage(evidence));
        check(wire.contains("input_image") && wire.contains("data:image/jpeg;base64,"),
            "Provider request must contain an actual input_image, not image-looking text");
        check(function.getImages().isEmpty(), "The persisted function message must remain image-free");
        AIVisualToolOutput.appendToRequest(request, result);
        check(request.size() == 2, "Repeated captures must replace the previous transient screenshot");
        List<AIMessage> ordinary = new ArrayList<>();
        AIMessage user = AIMessage.userMessage("My attachment").withImages(List.of(attachment));
        ordinary.add(user);
        AIFunctionResult fresh = new AIFunctionResult(AIFunctionType.INFORMATION,
            new AIVisualToolOutput("Another fixture", attachment));
        AIVisualToolOutput.appendToRequest(ordinary, fresh);
        check(ordinary.getFirst() == user && ordinary.size() == 2, "Ordinary user attachments must be preserved");
        System.out.println("Visual image tests passed; real input_image payload, no saved screenshot bytes, bounded encoding");
    }

    private static void check(boolean condition, @NotNull String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectInvalid(@NotNull Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected invalid capture bounds");
        } catch (IllegalArgumentException expected) {
            // Capture bounds are refused before allocating an image.
        }
    }
}
