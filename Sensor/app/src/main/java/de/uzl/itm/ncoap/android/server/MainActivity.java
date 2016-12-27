package de.uzl.itm.ncoap.android.server;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.*;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import de.uzl.itm.ncoap.android.server.dialog.SettingsDialog;
import de.uzl.itm.ncoap.android.server.dialog.StartRegistrationDialog;
import de.uzl.itm.ncoap.android.server.resource.*;
import de.uzl.itm.ncoap.android.server.task.AddressResolutionTask;
import de.uzl.itm.ncoap.android.server.task.ConnectivityChangeTask;
import de.uzl.itm.ncoap.android.server.task.ProxyRegistrationTask;
import de.uzl.itm.ncoap.application.endpoint.CoapEndpoint;
import de.uzl.itm.ncoap.communication.dispatching.server.NotFoundHandler;

import java.net.InetSocketAddress;


public class MainActivity extends AppCompatActivity implements RadioGroup.OnCheckedChangeListener,
        SettingsDialog.Listener, StartRegistrationDialog.Listener {

    private Handler handler = new Handler();

    private NetworkStateReceiver networkStateReceiver;

    //Sensors
    private SensorManager sensorManager;
    private LightSensorEventListener lightSensorListener;

    //View Elements
    private RadioGroup radGroupServer;
    private TextView txtIP;
    private TextView txtProxy;

    private RadioGroup radGroupLight;
    private RadioButton radLightOff;
    private EditText txtLight;
    private ProgressBar prbLight;

    private CoapEndpoint coapEndpoint;
    private AmbientBrightnessSensorResource lightSensorService;
    private LocationResource locationResource;
    private AmbientNoiseSensorResource ambientNoiseSensorResource;

    private SettingsDialog settingsDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Initialize the action bar
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setLogo(R.drawable.spitfire_logo);
        toolbar.setSubtitle(R.string.app_subtitle);
        setSupportActionBar(toolbar);

        //set view elements
        this.radGroupServer = (RadioGroup) findViewById(R.id.radgroup_server);
        this.txtIP = (TextView) findViewById(R.id.ip_txt);
        this.txtProxy = (TextView) findViewById(R.id.ssp_txt);

        this.radGroupLight = (RadioGroup) findViewById(R.id.radgroup_light);
        this.radLightOff = (RadioButton) findViewById(R.id.rad_light_off);
        this.txtLight = (EditText) findViewById(R.id.light_txt);
        this.prbLight = (ProgressBar) findViewById(R.id.light_prb);
        this.prbLight.setMax((int) Math.log(200000));

        this.networkStateReceiver = new NetworkStateReceiver(this);
        registerReceiver(networkStateReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));

        this.settingsDialog = null;
    }

    @Override
    protected void onStart() {
        super.onStart();

        //Server
        this.radGroupServer.setOnCheckedChangeListener(this);

        //Sensor Manager (for sensors)
        this.sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        //Register for RadioGroup "Light"
        this.radGroupLight.setOnCheckedChangeListener(this);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.ssp_settings) {
            if (this.settingsDialog == null) {
                this.settingsDialog = new SettingsDialog();
            }

            this.settingsDialog.show(getFragmentManager(), null);
            return true;
        }

        if (id == R.id.ssp_registration) {
            new StartRegistrationDialog().show(getFragmentManager(), null);
        }

        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(networkStateReceiver);
    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {
        //Server
        if (group.getId() == R.id.radgroup_server) {
            if (checkedId == R.id.rad_server_on) {
                this.coapEndpoint = new CoapEndpoint(
                        "Phone CoAP", NotFoundHandler.getDefault(), new InetSocketAddress(5683)
                );
            } else {
                final ProgressDialog progressDialog = new ProgressDialog(this);
                progressDialog.setMessage("Shutdown Server...");
                progressDialog.show();
                AsyncTask.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (coapEndpoint == null) {
                            Log.d("app", "Coap Endpoint is null, and it shouldn't !");
                            return;
                        }
                        ListenableFuture shutdownFuture = coapEndpoint.shutdown();
                        Futures.addCallback(shutdownFuture, new FutureCallback() {
                            @Override
                            public void onSuccess(Object result) {
                                coapEndpoint = null;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        progressDialog.dismiss();
                                    }
                                });
                            }

                            @Override
                            public void onFailure(Throwable t) {
                                onSuccess(null);
                            }
                        }, MoreExecutors.sameThreadExecutor());
                    }
                });

                //Disable Light Service
                if (!this.radLightOff.isChecked()) {
                    this.radLightOff.setChecked(true);
                }
            }
        }

        //Light
        else if(group.getId() == R.id.radgroup_light){
            if(checkedId == R.id.rad_light_on){
                //Check if there is a light sensor available
                Sensor lightSensor = this.sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                if(lightSensor == null){
                    this.radLightOff.setChecked(true);
                    Toast.makeText(this, "No light sensor available!", Toast.LENGTH_LONG).show();
                }
                else {
                    //create light sensor listener and register at sensor manager
                    this.lightSensorListener = new LightSensorEventListener();
                    this.sensorManager.registerListener(
                            this.lightSensorListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL
                    );

                    //Create Light Web Service
                    if (this.coapEndpoint != null) {
                        AmbientBrightnessSensorValue initialStatus = new AmbientBrightnessSensorValue(
                                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
                        );
                        this.lightSensorService = new AmbientBrightnessSensorResource(
                                "/light", initialStatus, coapEndpoint.getExecutor()
                        );
                        this.coapEndpoint.registerWebresource(this.lightSensorService);
                    } else {
                        this.radLightOff.setChecked(true);
                        Toast.makeText(this, "Server is not running!", Toast.LENGTH_LONG).show();
                    }
                }
            }
            else{
                sensorManager.unregisterListener(this.lightSensorListener);
                this.txtLight.setText("");
                this.prbLight.setProgress(0);
                if(this.lightSensorService != null) {
                    this.coapEndpoint.shutdownWebresource(this.lightSensorService.getUriPath());
                    this.lightSensorService = null;
                }
            }
        }
    }


    public void setTxtProxy(final String proxyIP){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                txtProxy.setText(proxyIP);
            }
        });
    }


    public void setTxtIP(final String serverIP){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                txtIP.setText(serverIP);
            }
        });
    }

    public Handler getHandler(){
        return this.handler;
    }

    public CoapEndpoint getCoapEndpoint(){
        return this.coapEndpoint;
    }

    @Override
    public void onProxyChanged(String sspHost) {
        new AddressResolutionTask(this).execute(sspHost);
    }


    @Override
    public void registerAtProxy() {
        new ProxyRegistrationTask(this).execute((String) txtProxy.getText());
    }

    private class NetworkStateReceiver extends BroadcastReceiver {

        //private ConnectivityManager connectivityManager;
        private WifiManager wifiManager;

        public NetworkStateReceiver(Activity activity){
            //this.connectivityManager = (ConnectivityManager) activity.getSystemService(CONNECTIVITY_SERVICE);
            this.wifiManager = (WifiManager) activity.getSystemService(WIFI_SERVICE);
        }

        public void onReceive(Context context, Intent intent) {
            Log.d("app", "Network connectivity change");
            new ConnectivityChangeTask(MainActivity.this).execute(null, null);
        }
    }

    private class LightSensorEventListener implements SensorEventListener{

        @Override
        public void onSensorChanged(final SensorEvent event) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    txtLight.setText("" + event.values[0]);
                    prbLight.setProgress((int) Math.log(event.values[0]));

                    if(lightSensorService != null) {
                        lightSensorService.setLightValue(new AmbientBrightnessSensorValue(0, 0, (double) event.values[0]));
                    }
                }
            });
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            //TODO but I don't know what that means
        }
    }

}

