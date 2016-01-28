package com.example;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;
import java.io.PrintStream;
import java.io.FileNotFoundException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;

import java.lang.StringBuilder;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.ByteOrder;

import android.os.AsyncTask;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.graphics.Bitmap;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.view.View;
import android.widget.Toast;

// For SSH connection, jar file is copied to project /libs
import com.jcraft.jsch.*;

// AsyncTask subclass for remote execution

class ComputationOnCloud extends AsyncTask<Integer, Void, String> {
	private static Session session;
	private static boolean isConnected = false;
	// the method handling the connection has to be called outside of the main thread
	// otherwise we get android.os.NetworkOnMainThreadException
	// session is created in the AsyncTask, runs in a seperate thread
	private void connect() {
		if (!isConnected) {
			try {
		      	JSch jsch = new JSch();
	          	session = jsch.getSession("USER_NAME", "SERVER_ADDRESS", 22);
	          	session.setPassword("PASSWORD");

	          	// Avoid asking for key confirmation
	          	Properties prop = new Properties();
	          	prop.put("StrictHostKeyChecking", "no");
	          	session.setConfig(prop);

	          	session.connect();
			} catch (JSchException e) {
				Log.i("clmandel", "connectSSH() " + e.getMessage());
			}
			isConnected = true;
		}
	}

	private void enableCompression() throws Exception {
		session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none");
		session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none");
		session.setConfig("compression_level", "9");
		session.rekey();
	}

	private void disableCompression() throws Exception {
		session.setConfig("compression.s2c", "none");
		session.setConfig("compression.c2s", "none");
		session.rekey();
	}
					
	@Override
    protected String doInBackground(Integer... params) {
		// we expect two parameters, the first one is the resolution, e.g., 1024 / 2048 / 4096
		// the second one is a workload split, 4 means all computataion is done on cloud
		// 3 means 75% computed on cloud, 2 means 50%, 1 means 25%. 0 is illegal, means all
		// computataion is done locally, in that case we should never invoke the ssh connection

		// we use a StringBuilder to read command execution output
		// have tested also ByteArrayOutputStream, which does not seem to work...
		StringBuilder strb = new StringBuilder("");
		connect();		
		try {
			long remote_compute_start = android.os.SystemClock.elapsedRealtimeNanos();
			// SSH Channel
		    ChannelExec channelssh = (ChannelExec) session.openChannel("exec");

			channelssh.setInputStream(null);			
			InputStream in = channelssh.getInputStream();

		    // Execute command
		    channelssh.setCommand("./clMandelbrot "+params[0]+" "+params[1]); // resolution, workload
			// To test the dual-channels connectivity, call channelssh.setCommand("pwd");
    		channelssh.connect();

			long remote_compute_complete = android.os.SystemClock.elapsedRealtimeNanos();

			Log.i("clmandel", "background - remote execution completes in ns = " + (remote_compute_complete - remote_compute_start));

			// Read command execution output
			int data = in.read();

			long read_first_char = android.os.SystemClock.elapsedRealtimeNanos();

			Log.i("clmandel", "background - read output first char in ns = " + (read_first_char - remote_compute_complete));

			while(data != -1)
			{
				strb.append((char)data);
				//Log.i("clmandel", "doInBackground read inputstream " + (char)data);
				data = in.read();
			}

    		channelssh.disconnect();

			long read_output_complete = android.os.SystemClock.elapsedRealtimeNanos();

			Log.i("clmandel", "background - read output in ns = " + (read_output_complete - remote_compute_complete));

			// in the same AsyncTask, the file is downloaded.

			// To use compression, call enableCompression();

			Channel channel = session.openChannel("sftp");
        	channel.connect();
        	ChannelSftp sftpChannel = (ChannelSftp) channel;

			//Log.i("clmandel", "sftp channel opens and we are about to send the file");
			long transfer_start = android.os.SystemClock.elapsedRealtimeNanos();
			
			Log.i("clmandel", "background - open sftp channel in ns = " + (transfer_start - read_output_complete));

        	sftpChannel.get("output.bin", "/data/data/com.example/server_response.bin");

			long transfer_complete = android.os.SystemClock.elapsedRealtimeNanos();

			Log.i("clmandel", "background - transfer file in ns = " + (transfer_complete - transfer_start));

        	sftpChannel.exit();

			// To disable compression after transfer, call disableCompression();
    	}
    	catch (Exception e) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
    		PrintStream ps = new PrintStream(baos);
    		e.printStackTrace(ps);
    		ps.close();
			Log.i("clmandel", "doInBackground " + baos.toString());
    	}
		Log.i("clmandel", "doInBackground() - compute time reported by clouds:" + strb.toString());
		return strb.toString();
    }
}

// Main activity 

public class MandelbrotOpenCLActivity extends Activity implements OnItemSelectedListener{

	static {
        System.loadLibrary("JNIComputation");
    }
	
	native private boolean compileKernels();
	native private int mandelbrot(short[] colorArray, int width, int height);
	
	private int mWidth = 1024;
	private int mHeight = 1024;
	private int mWorkSplit = 4; // default, all computation on client
	private short[] mLocalComputeColors = new short[mWidth*mHeight];
	private int[] mColors = new int[mWidth*mHeight];
	private short[] mPrimeColors;

	private Session mSession;
	private int mComputeTime;
	private ImageView mImageView;
	private TextView mTextView, mServerResponse;
    private Spinner mSpinnerR;
    private Spinner mSpinnerW;
	private android.util.DisplayMetrics mMetrics;

    private void copyFile(final String f) {
		InputStream in;
		try {
			in = getAssets().open(f);
			final File of = new File(getDir("execdir",MODE_PRIVATE), f);

			final OutputStream out = new FileOutputStream(of);

			final byte b[] = new byte[65535];
			int sz = 0;
			while ((sz = in.read(b)) > 0) {
				out.write(b, 0, sz);
			}
			in.close();
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void convertToIntColors(int[] intColors, short[] shortColors, int width, int height) {
    	for (int i = height - 1; i >= 0; i--) {
        	for (int j = 0; j < width; j++) {
            	int ipos = width * i + j;
				int c = 0xFFFF & (shortColors[ipos]);
				if ( c == 0) {
					// inside the Mandelbrot set
					intColors[ipos] = android.graphics.Color.rgb(0,0,0);
				} 
				else {
					double s = 3*Math.log(c)/Math.log(65535);
					if (s < 1) {
						intColors[ipos] = android.graphics.Color.rgb(0,0,(int)(255*s));
					}
					else if (s < 2)	{
						intColors[ipos] = android.graphics.Color.rgb(0,(int)(255*(s-1)),255);
					}
					else {
						intColors[ipos] = android.graphics.Color.rgb((int)(255*(s-2)),255,255);
					}
				} 
        	} // inner for loop
    	} // outer for loop
	}

	public static String exceptionStacktraceToString(Exception e) {
	    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    	PrintStream ps = new PrintStream(baos);
    	e.printStackTrace(ps);
    	ps.close();
    	return baos.toString();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_mandelbrot_opencl);
        copyFile("kernels.cl");

		mComputeTime = mandelbrot(mLocalComputeColors, mWidth, mHeight);
		
		mMetrics = new android.util.DisplayMetrics();
 		getWindowManager().getDefaultDisplay().getMetrics(mMetrics);
		convertToIntColors(mColors, mLocalComputeColors, mWidth, mHeight);
		Bitmap bmp = android.graphics.Bitmap.createBitmap(mMetrics, mColors, mWidth, mHeight, android.graphics.Bitmap.Config.ARGB_8888);

		mImageView = (ImageView) findViewById(R.id.image_view);		
		mImageView.setImageBitmap(bmp);

		mSpinnerR = (Spinner) findViewById(R.id.resolution);
		ArrayAdapter<CharSequence> adapterR = ArrayAdapter.createFromResource(this, R.array.resolution, android.R.layout.simple_spinner_item);
		adapterR.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		mSpinnerR.setAdapter(adapterR);
		mSpinnerR.setOnItemSelectedListener(this);

		mSpinnerW = (Spinner) findViewById(R.id.workload);
		ArrayAdapter<CharSequence> adapterW = ArrayAdapter.createFromResource(this, R.array.workload, android.R.layout.simple_spinner_item);
		adapterW.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		mSpinnerW.setAdapter(adapterW);
		mSpinnerW.setOnItemSelectedListener(this);

		mTextView = (TextView) findViewById(R.id.text_view);
		mTextView.setText("OpenCL computation time in us: "+ mComputeTime);

		mServerResponse = (TextView) findViewById(R.id.server_response);
		mServerResponse.setText("response from cloud");
	}

	public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
		// To test the item selection, use the Toast code below		
		// Toast.makeText(parent.getContext(), "Listener: " + parent.getItemAtPosition(pos).toString(), Toast.LENGTH_SHORT).show();
	 	switch (parent.getId()) {
			case R.id.resolution:

				switch(pos) {
					case 0:				
						mWidth = 1024;
						mHeight = 1024;
						break;
					case 1:
						mWidth = 2048;
						mHeight = 2048;
						break;
					case 2:
						mWidth = 4096;
						mHeight = 4096;
						break;
				} 
				
				break;
			
			case R.id.workload:
				// we don't modify mHeight here, the modification can be over-written by the mSpinnerR, which also modifies mHeight
				switch(pos) {
					case 0: // pos == 0, all computation on client, default value, mWorkSplit = 4				
						mWorkSplit = 4;
						break;
					case 1: // 75% of computation on client, mWorkSplit = 3
						mWorkSplit = 3;
						break;
					case 2: // 50% of computation on client, mWorkSplit = 2
						mWorkSplit = 2;
						break;
					case 3: // 25% of computation on client, mWorkSplit = 1
						mWorkSplit = 1;
						break;
					case 4: // all computation on server, mWorkSplit = 0
						mWorkSplit = 0;
						break;					
				}
				
				break;
		}
    }

	public void onNothingSelected(AdapterView<?> parent) {
    }
		
	public void reCaculate(View view) {
		
		Log.i("clmandel", "reCaculate() mWorkSplit = " + mWorkSplit);
		mHeight = mWidth*mWorkSplit/4;	

		if (mWidth == 4096 || mHeight == 4096) { // we don't draw this huge picture, only update computation time.
			// alternatively, we can draw only part of the mandelbrot, e.g., 1/4 of the upper right corner
			mComputeTime = mandelbrot(null, mWidth, mHeight);
		} 
		else if (mWorkSplit == 0) { // all computation on server			
			String s = "";
			// mHeight has a value of 0, because the mWorkSplit is 0 when all are calculated on server
			// We pass mWidth in the place of mHeight, as we deal with a squre (height == width)
			mColors = new int[mWidth*mWidth];
			byte[] cloudComputeColors = {};

			long start_time = android.os.SystemClock.elapsedRealtimeNanos();

			try {
				s = new ComputationOnCloud().execute(mWidth, 4).get();
			}
			catch (Exception e) {
    			Log.i("clmandel", exceptionStacktraceToString(e));
			}

			long read_start = android.os.SystemClock.elapsedRealtimeNanos();

			Log.i("clmandel", "reCalculate() - asynctask completes in ns = " + (read_start - start_time));
			
			// read file into a byte array

			try {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				//Log.i("clmandel", "MainActivity:reCalculate() - we are about to open the same file");
				FileInputStream is = new FileInputStream("/data/data/com.example/server_response.bin");
				//Log.i("clmandel", "reCalculate() - file opens OK");
				byte[] b = new byte[1024];
				int bytesRead = is.read(b);
				while (bytesRead != -1) {
   					bos.write(b, 0, bytesRead);
					//Log.i("clmandel", "MainActivity:reCalculate() - Again we read files to bytearray " + bytesRead);
					bytesRead = is.read(b);
				}
				//Log.i("clmandel", "reCalculate() - read file completely");
				cloudComputeColors = bos.toByteArray();
				bos.close();
			}
			catch (FileNotFoundException e) {
				Log.i("clmandel", exceptionStacktraceToString(e));
			}
			catch (IOException e) {
				Log.i("clmandel", exceptionStacktraceToString(e));
			}

			long read_complete = android.os.SystemClock.elapsedRealtimeNanos();

			int c_length = cloudComputeColors.length;

			Log.i("clmandel", "reCalculate() - read file in ns = " + (read_complete - read_start) + ", length = " + c_length);

			Log.i("clmandel", "reCalculate() - full computation in ns = " + (read_complete - start_time));

			// this check ensures the code does not throw ArrayIndexOutOfBoundsException when the cloud compute fails
			// i.e. the file is computed from the last working asynctask call, the length can be equal (we made the exact same 
			// computation in the last call), or smaller or bigger.
			if (c_length == 2*mWidth*mWidth) {
				mComputeTime = (int)((read_complete - start_time)/1000);
				mServerResponse.setText("Response from server: " + s);

				// convert byte to short
				short[] shortColors = new short[c_length/2];
				ByteBuffer.wrap(cloudComputeColors).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortColors);
				
				convertToIntColors(mColors, shortColors, mWidth, mWidth);
			
				// draw mandelbrot
				Bitmap bmp = android.graphics.Bitmap.createBitmap(mMetrics, mColors, mWidth, mWidth, android.graphics.Bitmap.Config.ARGB_8888);
				mImageView.setImageBitmap(bmp);
			}
		}
		else if (mWorkSplit == 4) { // all computation on local
    		mPrimeColors = new short[mWidth*mHeight];
			mColors = new int[mWidth*mHeight];
			long start_time = android.os.SystemClock.elapsedRealtimeNanos();
			int openclcomputetime = mandelbrot(mPrimeColors, mWidth, mHeight);
			mComputeTime = (int)((android.os.SystemClock.elapsedRealtimeNanos() - start_time)/1000);
			Log.i("clmandel", "reCalculate() - OpenCLComputeTime = " + openclcomputetime);
			convertToIntColors(mColors, mPrimeColors, mWidth, mHeight);
			Bitmap bmp = android.graphics.Bitmap.createBitmap(mMetrics, mColors, mWidth, mHeight, android.graphics.Bitmap.Config.ARGB_8888);
			mImageView.setImageBitmap(bmp);
		}			
		else {	// workload split
			// Computation on local
    		mLocalComputeColors = new short[mWidth*mHeight];
			mPrimeColors = new short[mWidth*mWidth];
			mColors = new int[mWidth*mWidth];
			byte[] cloudComputeColors = {};
			String s = "";

			long start_time = android.os.SystemClock.elapsedRealtimeNanos();

			mandelbrot(mLocalComputeColors, mWidth, mHeight);

			long local_compute_complete = android.os.SystemClock.elapsedRealtimeNanos();

			Log.i("clmandel", "reCalculate() - local computation in ns = " + (local_compute_complete - start_time));

			// Computation on cloud
			try {
				s = new ComputationOnCloud().execute(mWidth, (4 - mWorkSplit)).get();
			}
			catch (Exception e) {
    			Log.i("clmandel", exceptionStacktraceToString(e));
			}

			long read_start = android.os.SystemClock.elapsedRealtimeNanos();

			Log.i("clmandel", "reCalculate() - asynctask completes in ns = " + (read_start - local_compute_complete));

			try {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				//Log.i("clmandel", "MainActivity:reCalculate() - we are about to open the same file");
				FileInputStream is = new FileInputStream("/data/data/com.example/server_response.bin");
				//Log.i("clmandel", "reCalculate() - file opens OK");
				byte[] b = new byte[1024];
				int bytesRead = is.read(b);
				while (bytesRead != -1) {
   					bos.write(b, 0, bytesRead);
					//Log.i("clmandel", "MainActivity:reCalculate() - Again we read files to bytearray " + bytesRead);
					bytesRead = is.read(b);
				}
				//Log.i("clmandel", "reCalculate() - read file completely");
				cloudComputeColors = bos.toByteArray();
				bos.close();
			}
			catch (FileNotFoundException e) {
				Log.i("clmandel", exceptionStacktraceToString(e));
			}
			catch (IOException e) {
				Log.i("clmandel", exceptionStacktraceToString(e));
			}

			long read_complete = android.os.SystemClock.elapsedRealtimeNanos();

			int c_length = cloudComputeColors.length;

			Log.i("clmandel", "reCalculate() - read file in ns = " + (read_complete - read_start) + ", length = " + c_length);

			if (c_length == 2*mWidth*(mWidth - mHeight)) {
				int l1 = mLocalComputeColors.length;
				int l2 = c_length/2;

				// convert byte to short
				short[] shortColors = new short[l2];
				ByteBuffer.wrap(cloudComputeColors).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortColors);
					 				
				System.arraycopy(mLocalComputeColors, 0, mPrimeColors, 0, l1);
				System.arraycopy(shortColors, 0, mPrimeColors, l1, l2);

				long complete_time = android.os.SystemClock.elapsedRealtimeNanos();
 
				Log.i("clmandel", "reCalculate() - concatenate arrays in ns = " + (complete_time - read_complete));

				mComputeTime = (int)((complete_time - start_time)/1000);

				Log.i("clmandel", "reCalculate() - full computation in ns = " + (complete_time - start_time));

				mServerResponse.setText("Response from server: " + s);

				// Combine the output from local and cloud to represent the complete mandelbrot set
				convertToIntColors(mColors, mPrimeColors, mWidth, mWidth);
				Bitmap bmp = android.graphics.Bitmap.createBitmap(mMetrics, mColors, mWidth, mWidth, android.graphics.Bitmap.Config.ARGB_8888);
				mImageView.setImageBitmap(bmp);
			}
		}
		mTextView.setText("Total computation time in us: "+ mComputeTime);		
	}

	@Override
	protected void onPause() {
		super.onPause();
	}

	@Override
	protected void onResume() {
		super.onResume();
	}
}
