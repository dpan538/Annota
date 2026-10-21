package io.github.dpan538.annota.testsender;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;

/** A sender with its own UID and temporary read grant; packaged only in the test APK. */
public class SenderActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            File file = new File(getCacheDir(), "incoming.pdf");
            try (OutputStream out = new FileOutputStream(file)) {
                if (getIntent().getBooleanExtra("invalid", false)) out.write("not a PDF".getBytes("UTF-8"));
                else try (InputStream in = createPackageContext("io.github.dpan538.annota", 0).getAssets().open("attention-is-all-you-need.pdf")) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                }
            }
            Uri uri = Uri.parse("content://io.github.dpan538.annota.test.incoming/asset/42");
            String action = getIntent().getStringExtra("action");
            if (action == null) action = Intent.ACTION_VIEW;
            Intent send = new Intent(action).setPackage("io.github.dpan538.annota");
            if (Intent.ACTION_SEND.equals(action)) {
                send.setType("application/pdf"); send.putExtra(Intent.EXTRA_STREAM, uri);
            } else {
                String mime = getIntent().getStringExtra("mime");
                send.setDataAndType(uri, mime == null ? "application/pdf" : mime);
            }
            if (!getIntent().getBooleanExtra("noGrant", false)) {
                send.setClipData(ClipData.newRawUri("PDF", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(send);
        } catch (Exception e) { throw new IllegalStateException(e); }
        finish();
    }
}
