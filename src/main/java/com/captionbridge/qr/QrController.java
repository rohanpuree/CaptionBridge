package com.captionbridge.qr;

import com.captionbridge.config.JoinUrl;
import com.captionbridge.session.SessionService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

@RestController
public class QrController {
    private static final int SIZE = 320;
    private final SessionService sessions;
    private final JoinUrl joinUrl;

    public QrController(SessionService sessions, JoinUrl joinUrl) {
        this.sessions = sessions;
        this.joinUrl = joinUrl;
    }

    @GetMapping(path = "/api/sessions/{id}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable UUID id, HttpServletRequest request)
            throws WriterException, IOException {
        sessions.get(id);
        var matrix = new QRCodeWriter().encode(joinUrl.forSession(request, id), BarcodeFormat.QR_CODE, SIZE, SIZE);
        var png = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", png);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG).body(png.toByteArray());
    }
}
