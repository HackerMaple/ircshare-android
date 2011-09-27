package epeli.ircshare;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.UUID;

import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreProtocolPNames;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.ClipboardManager;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class PostToIRC extends Activity {
	public static final String PREFS_NAME = "IRCSharePrefs";

	Bundle extras;
	Intent intent;
	String action;

	Button send;
	TextView status;
	EditText channel;
	EditText nick;
	EditText caption;
	Spinner network;

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);

		ImageView image = (ImageView) findViewById(R.id.imageview);

		network = (Spinner) findViewById(R.id.network);
		channel = (EditText) findViewById(R.id.channel);
		nick = (EditText) findViewById(R.id.nick);
		caption = (EditText) findViewById(R.id.caption);

		status = (TextView) findViewById(R.id.status);
		send = (Button) findViewById(R.id.send);

		channel.setText(settings.getString("channel", "#"));
		nick.setText(settings.getString("nick", ""));
		network.setSelection(settings.getInt("network", 0));

		ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
				this, R.array.networks, android.R.layout.simple_spinner_item);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		network.setAdapter(adapter);

		intent = getIntent();
		extras = intent.getExtras();
		action = intent.getAction();

		if (isCalledByGallery()) {
			setupSmallImg(image);
		} else {
			notify("Not called by gallery. Nothing to upload.");
			setResult(RESULT_OK);
			finish();
		}

		send.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				if (!isCalledByGallery()) {
					PostToIRC.this
							.notify("Not called by gallery. Nothing to upload.");
				}

				if (channel.getText().length() <= 1) {
					PostToIRC.this.notify("Set channel");
					return;
				}
				if (nick.getText().length() == 0) {
					PostToIRC.this.notify("Set nick name");
					return;
				}

				new UploadImageTask().execute();

			}
		});

	}

	protected void onPause() {
		super.onPause();
		SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
		Editor editor = settings.edit();
		editor.putString("channel", channel.getText().toString());
		editor.putString("nick", nick.getText().toString());
		editor.putInt("network", network.getSelectedItemPosition());
		editor.commit();
	}

	private boolean isCalledByGallery() {
		return Intent.ACTION_SEND.equals(action)
				&& extras.containsKey(Intent.EXTRA_STREAM);
	}

	private void notify(CharSequence text) {
		Context context = getApplicationContext();
		Toast toast = Toast.makeText(context, text, Toast.LENGTH_LONG);
		toast.show();
		status.setText(text);
	}

	private InputStream getImgInputStream() throws FileNotFoundException {
		// Get resource path from intent callee
		Uri uri = (Uri) extras.getParcelable(Intent.EXTRA_STREAM);

		// Query gallery for camera picture via
		// Android ContentResolver interface
		ContentResolver cr = getContentResolver();
		return cr.openInputStream(uri);

	}

	private Uri getImgUri() {
		return (Uri) extras.getParcelable(Intent.EXTRA_STREAM);
	}

	private AssetFileDescriptor getImgFile() throws FileNotFoundException {
		ContentResolver cr = getContentResolver();
		return cr.openAssetFileDescriptor(getImgUri(), "r");
	}

	private String getImgMimeType() {
		ContentResolver cr = getContentResolver();
		return cr.getType(getImgUri());
	}

	private void setupSmallImg(ImageView img) {

		Bitmap bitmap;
		try {
			bitmap = BitmapFactory.decodeStream(getImgInputStream());
			double scale = (double) bitmap.getHeight()
					/ (double) bitmap.getWidth();
			img.setImageBitmap(Bitmap.createScaledBitmap(bitmap, 400,
					(int) (400.0 * scale), true));
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private class UploadImageTask extends AsyncTask<Void, Void, Void> {

		String res;
		String err;

		/**
		 * Escape non-ascii characters and try to compatible with
		 * querystring.unescape of Node.js.
		 * 
		 * @param s
		 * @throws UnsupportedEncodingException
		 */
		private StringBody escapeToStringBody(String s)
				throws UnsupportedEncodingException {
			s = URLEncoder.encode(s);
			s = s.replace("+", "%20");
			return new StringBody(s);

		}

		private void uploadImage() {

			HttpClient httpclient = new DefaultHttpClient();
			httpclient.getParams().setParameter(
					CoreProtocolPNames.PROTOCOL_VERSION, HttpVersion.HTTP_1_1);
			HttpPost httppost = new HttpPost(
					Config.postURL);
			
			PackageManager pm = getPackageManager();
			PackageInfo info;
			
			int versionCode;
			String versionName;
			
			try {
				info = pm.getPackageInfo("epeli.ircshare", 0);
				versionName = info.versionName;
				versionCode = info.versionCode;
			} catch (NameNotFoundException e1) {
				versionName = "Unknown version";
				versionCode = -1;				
			}
			
			
			try {

				MultipartEntity entity = new MultipartEntity(
						HttpMultipartMode.STRICT, null,
						Charset.forName("UTF-8"));

				entity.addPart("nick", escapeToStringBody(nick.getText()
						.toString()));
				
				entity.addPart("channel", escapeToStringBody(channel.getText()
						.toString()));

				entity.addPart("caption", escapeToStringBody(caption.getText()
						.toString()));

				entity.addPart("network", escapeToStringBody(network
						.getSelectedItem().toString()));
				
				UUID uuid = new DeviceUuidFactory(getApplicationContext()).getDeviceUuid();
				entity.addPart("deviceid", escapeToStringBody(uuid.toString()));
				
				entity.addPart("devicename", escapeToStringBody(Build.MODEL));
				
				entity.addPart("versionname", escapeToStringBody( versionName ));
				entity.addPart("versioncode", escapeToStringBody( "" + versionCode ));
				
				entity.addPart("picdata", new InputStreamBody(
						getImgInputStream(), getImgMimeType(), "pic.jpg"));

				httppost.setEntity(entity);
				ResponseHandler<String> responseHandler = new BasicResponseHandler();
				res = httpclient.execute(httppost, responseHandler);

			} catch (ClientProtocolException e) {
				err = e.toString();
			} catch (IOException e) {
				err = e.toString();
			}

		}

		@Override
		protected Void doInBackground(Void... params) {
			uploadImage();
			return null;
		}

		public void onPreExecute() {
			PostToIRC.this.notify("Sending image...");
		}

		public void onPostExecute(Void n) {
			JSONObject json = null;
			try {
				json = new JSONObject(res);
				
				if (json.has("error") ) {
					err = json.getString("message");
				}
				
			} catch (JSONException e) {
				err = "Got mallformed json response from the server";
			}
			
			
			
			if (err == null) {
				ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
				String url;
				
				try {
					url = json.getString("url");
				} catch (JSONException e) {
					url = "no url";
				}
				
				
				clipboard.setText(url);
				
				
				PostToIRC.this.notify("Image sent! Copied url to clipboard: "
						+ url);
			} else {
				PostToIRC.this.notify("Error sending picture: " + err);
			}
		}

	}

}