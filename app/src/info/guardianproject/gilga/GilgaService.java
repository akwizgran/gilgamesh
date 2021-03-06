package info.guardianproject.gilga;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.StringTokenizer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.ChannelListener;
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

public class GilgaService extends Service {

	private final static String TAG = "GilgaService";

	public final static String ACTION_NEW_MESSAGE = "action_new_message";
	
    private static Hashtable<String,Status> mMessageLog = null;
    
    private Handler mTimerHandler = new Handler();
    private final static int BLUETOOTH_DISCOVERY_RETRY_TIMEOUT = 3000;
    
    // Local Bluetooth adapter
   private BluetoothAdapter mBluetoothAdapter = null;
   
   //Local Device Address
   private String mLocalShortBluetoothAddress = "";
   private String mLocalAddressHeader = "";
   
   //for WifiP2P mode
   WifiP2pManager mWifiManager = null;
   Channel mWifiChannel = null;

   boolean mRepeaterMode = false; //by default RT trusted messages

    // String buffer for outgoing messages
    private StringBuffer mOutStringBuffer;

    private StatusAdapter mStatusAdapter;
    
	@Override
	public IBinder onBind(Intent arg0) {
		// TODO Auto-generated method stub
		return null;
	}
	
	
	
	@Override
	public void onCreate() {
		super.onCreate();
		
		init();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
	
		if (intent != null)
		{
			if (intent.hasExtra("status"))
			{
				String status = intent.getStringExtra("status");
				
				if (status.matches("^(d |dm ).*$"))
				{
					sendDirectMessage (status);
				}
				else
					updateStatus (status);
				
			}
		}
		
		startListening();
		
		
		//based on intent, startListening() or startBroadcasting or sendMessage()
		
		return super.onStartCommand(intent, flags, startId);
	}



	@Override
    public void onDestroy() {
        super.onDestroy();
        
        // Stop the Bluetooth chat services
        if (mDirectChatSession != null) mDirectChatSession.stop();
        
        // Make sure we're not doing discovery anymore
        if (mBluetoothAdapter != null && mBluetoothAdapter.isDiscovering())
        	mBluetoothAdapter.cancelDiscovery();
        
        this.unregisterReceiver(mReceiver);

    }


	public static Hashtable<String,Status> getMessageLog ()
	{
		return mMessageLog;
	}

	private void init ()
	{
		mStatusAdapter = StatusAdapter.getInstance(this);
		
		mMessageLog = new Hashtable<String,Status>();
		
        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        mLocalShortBluetoothAddress = mapToNickname(mBluetoothAdapter.getAddress());
        mLocalAddressHeader = mLocalShortBluetoothAddress.substring(0,5);
       // mChatService = new BluetoothChatService(this, mHandler);

        mWifiManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        
        mWifiChannel = mWifiManager.initialize(this, getMainLooper(), new ChannelListener()
        {

			@Override
			public void onChannelDisconnected() {
				Log.d(TAG,"wifi p2p disconnected");
			}
        	
        });
        
        
        IntentFilter filter = new IntentFilter();
        // Register for broadcasts when a device is discovered
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        // Register for broadcasts when discovery has finished
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

        //  Indicates a change in the Wi-Fi P2P status.
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);

        // Indicates a change in the list of available peers.
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);

        // Indicates the state of Wi-Fi P2P connectivity has changed.
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        // Indicates this device's details have changed.
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        
        this.registerReceiver(mReceiver, filter);
        
        // Initialize the buffer for outgoing messages
        mOutStringBuffer = new StringBuffer("");

	}
    // The BroadcastReceiver that listens for discovered devices and
    // changes the title when discovery is finished
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                
                if (device.getName() != null)
                {
                	String address = device.getAddress();
                	
                	processInboundMessage(device.getName(),address,device.getBondState() == BluetoothDevice.BOND_BONDED);
                	
                	if (mQueuedDirectMessage.size() > 0 && mDirectChatSession != null
                			&& (mDirectChatSession.getState() != DirectMessageSession.STATE_CONNECTED 
                			|| mDirectChatSession.getState() != DirectMessageSession.STATE_CONNECTING))
                	{
                		//try to do resend now if address matches
                		
                		if (address != null)
                    	{
                			synchronized (mQueuedDirectMessage)
                			{
	                    		Iterator<DirectMessage> itDm = mQueuedDirectMessage.iterator();
	                    		while (itDm.hasNext())
	                    		{
	                    			DirectMessage dm = itDm.next();
	                    			
	                    			if (dm.to.equals(address))
	                    			{
	                    		    	boolean isSecure = device.getBondState()==BluetoothDevice.BOND_BONDED;
	                    		    	mDirectChatSession.connect(device, isSecure);
	                    		    	break;
	                    			}
	                    		}
                			}
                    	}
                	}
                	
                }
                
            // When discovery is finished, change the Activity title
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                
            	mHandler.postDelayed(new Runnable ()
            	{
            		public void run ()
            		{
                        mBluetoothAdapter.startDiscovery();
            		}
            	}, BLUETOOTH_DISCOVERY_RETRY_TIMEOUT);
            }
            else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                // Determine if Wifi P2P mode is enabled or not, alert
                // the Activity.
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                //	Toast.makeText(GilgaMesh.this, "Wifi P2P enhanced mode activated!", Toast.LENGTH_LONG).show();
                
                } 
                
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {

                // The peer list has changed!  We should probably do something about
                // that.
                mWifiManager.requestPeers(mWifiChannel, peerListListener);

            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {

                // Connection state changed!  We should probably do something about
                // that.
            	   NetworkInfo networkInfo = (NetworkInfo) intent
                           .getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

                   mWifiManager.requestConnectionInfo(mWifiChannel, new ConnectionInfoListener()
                   {

					@Override
					public void onConnectionInfoAvailable(WifiP2pInfo winfo) {
						
						
					}
                	   
                   });

            	//Log.d(TAG,"connection changed");
            	
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                
            	WifiP2pDevice device = (WifiP2pDevice) intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);

            	 boolean trusted = false; //not sure how to do this with wifi
            	 
            	 if (!mapToNickname(device.deviceAddress).startsWith(mLocalAddressHeader)) //not me
           	  		processInboundMessage(device.deviceName,device.deviceAddress,trusted);
            }
        }
    };
    
    private void processInboundMessage (String name, String address, boolean trusted)
    {
    	String messageBuffer = name;
    	
    	StringTokenizer st = new StringTokenizer(messageBuffer,"\n");
    	
    	while (st.hasMoreTokens())
    	{
    	
    		String message = st.nextToken();
    		
        	if (message.startsWith("#")||message.startsWith("!")||message.startsWith("@")||message.startsWith(" "))
        	{
        		message = message.trim();
        		
        		Status status = new Status();
        		status.from = address;
        		status.body = message;
        		status.trusted = trusted;
        		status.ts = new java.util.Date().getTime();
        		
            	if (isNewMessage(status)) //have we seen this message before
            	{	
            		mStatusAdapter.add(status);
            		
            		if (trusted && mRepeaterMode             				
            						&& (!message.contains('@' + mLocalAddressHeader))
            				) //don't RT my own tweet
            		{
            			String rtMessage = "RT @" + mapToNickname(status.from) + ": " + status.body;
            			updateStatus(rtMessage); //retweet!
            			
            		}
            		
            	}
            
        	}
    	}
    }
    
    
    
    private boolean isNewMessage (Status msg)
    {
    	
    	if (msg.body.indexOf(':')!=-1)
    	{
    	    
    		String messageBody = msg.body.substring(msg.body.lastIndexOf(':')+1).trim();
    		String hash = MD5(messageBody);
    		
    		if (mMessageLog.containsKey(hash))
    			return false;
    		else
    		{
    			mMessageLog.put(hash, msg);
    			return true;
    		}
    	}
    	else
    	{
    		String hash = MD5(msg.body);
    		if (mMessageLog.containsKey(hash))
    			return false;
    		else
    		{
    			mMessageLog.put(hash, msg);
    			return true;
    		}
    	}
    	
    	
    }
    
    private void setWifiDeviceName (String newDeviceName)
    {
    	try
        {
    		ActionListener al = new ActionListener ()
    		{

				@Override
				public void onFailure(int arg0) {
			//		Log.d(TAG,"could not set wifi device name: " + arg0);
				}

				@Override
				public void onSuccess() {
					
				//	Log.d(TAG,"set new device name!");
					
				}
    			
    		};
    		
	        Class c;
	        c = Class.forName("android.net.wifi.p2p.WifiP2pManager");
	        Method m = c.getMethod("setDeviceName", new Class[] {Channel.class, String.class, ActionListener.class});
	        Object o = m.invoke(mWifiManager, new Object[]{mWifiChannel,newDeviceName, al});
	        
        }
        catch (Exception e){
        	
        	Log.e(TAG,"error setting wifi name",e);
        }
    }
    
    private void startBroadcasting() {
      //  if(D) Log.d(TAG, "ensure discoverable");
       if (mBluetoothAdapter.getScanMode() !=
          BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
        	
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 3600);
            discoverableIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(discoverableIntent);
            
        }
       
       if (!mBluetoothAdapter.isDiscovering())
    	   mBluetoothAdapter.startDiscovery();

       if (mDirectChatSession == null)
       {
    	   mDirectChatSession = new DirectMessageSession(this, mHandler);
    	   mDirectChatSession.start();
       }
       else {
           // Only if the state is STATE_NONE, do we know that we haven't started already
           if (mDirectChatSession.getState() == DirectMessageSession.STATE_NONE) {
             // Start the Bluetooth chat services
        	   mDirectChatSession.start();
           }
           
       }
        
    }
    
    private void startListening ()
    {
        
    	if (!mBluetoothAdapter.isDiscovering())
    		mBluetoothAdapter.startDiscovery();
        
        startWifiDiscovery ();
       
    }
    
    private ArrayList<DirectMessage> mQueuedDirectMessage = new ArrayList<DirectMessage>();
    private DirectMessageSession mDirectChatSession;
    
    private void sendDirectMessage (String message)
    {
    	
    	StringTokenizer st = new StringTokenizer(message," ");
    	String cmd = st.nextToken();
    	String address = st.nextToken();
    	
    	StringBuffer dMessage = new StringBuffer();
    	
    	while (st.hasMoreTokens())
    		dMessage.append(st.nextToken()).append(" ");
    	
    	DirectMessage dm = new DirectMessage();
    	dm.to = address;
    	dm.body = dMessage.toString().trim();
    	dm.ts = new java.util.Date().getTime();
    	dm.delivered = false;

    	mQueuedDirectMessage.add(dm);
    	
    	final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
    	final boolean isSecure = device.getBondState()==BluetoothDevice.BOND_BONDED;
    	
    	dm.trusted = isSecure;
    	mStatusAdapter.add(dm);
    	
    	if (mDirectChatSession == null)
    	{
    	 mDirectChatSession = new DirectMessageSession(this, mHandler);
  	   	 mDirectChatSession.start();
    	}
    	else
    	{
    		mDirectChatSession.disconnect();
    	}
    	
    	mHandler.postAtTime(new Runnable ()
    	{
    		public void run ()
    		{
    	    	mDirectChatSession.connect(device, isSecure);
    		}
    	}, 2000);
    	
    }
    
    
    /**
     * Sends a message.
     * @param message  A string of text to send.
     */
    private void updateStatus(String message) {

        // Check that there's actually something to send
        if (message.length() > 0) {
           
        	mOutStringBuffer.append(' ' + message + '\n');

        	if (mOutStringBuffer.toString().getBytes().length > 248)
        	{
        		  mOutStringBuffer.setLength(0);
        		  mOutStringBuffer.append(' ' + message + '\n');
        	}
        	
        	mBluetoothAdapter.setName(mOutStringBuffer.toString());
        	
        	setWifiDeviceName(' ' + message);
        	
    		startBroadcasting() ;
            
            
        }
    }
  
    
    private void startWifiDiscovery ()
    {
    	
    	 mWifiManager.discoverPeers(mWifiChannel, new WifiP2pManager.ActionListener() {

             @Override
             public void onSuccess() {
                 // Code for when the discovery initiation is successful goes here.
                 // No services have actually been discovered yet, so this method
                 // can often be left blank.  Code for peer discovery goes in the
                 // onReceive method, detailed below.
             	Log.d(TAG,"success on wifi discover");
             	//startWifiDiscovery();
             }

             @Override
             public void onFailure(int reasonCode) {
                 // Code for when the discovery initiation fails goes here.
                 // Alert the user that something went wrong.
             	Log.d(TAG,"FAIL on wifi discovery: " + reasonCode);
             }

    	    });
    }
    
    private PeerListListener peerListListener = new PeerListListener() {
        @Override
        public void onPeersAvailable(WifiP2pDeviceList peerList) {

          Collection<WifiP2pDevice> deviceList = peerList.getDeviceList();

          for (WifiP2pDevice device : deviceList)
          {
        	  boolean trusted = false; //not sure how to do this with wifi
        	  
         	 if (!mapToNickname(device.deviceAddress).startsWith(mLocalAddressHeader)) //not me
        		  processInboundMessage(device.deviceName,device.deviceAddress,trusted);
        	  
        	  
        	  
          }
        }
    };


    
    public static String mapToNickname (String hexAddress)
    {
    	//remove : and get last 6 characters
    	hexAddress = hexAddress.replace(":", "");
    	hexAddress = hexAddress.substring(hexAddress.length()-6,hexAddress.length());
    	return hexAddress.toUpperCase();
    }
    
    public String MD5(String md5) {
    	   try {
    	        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
    	        byte[] array = md.digest(md5.getBytes());
    	        StringBuffer sb = new StringBuffer();
    	        for (int i = 0; i < array.length; ++i) {
    	          sb.append(Integer.toHexString((array[i] & 0xFF) | 0x100).substring(1,3));
    	       }
    	        return sb.toString();
    	    } catch (java.security.NoSuchAlgorithmException e) {
    	    }
    	    return null;
    	}
    
 // The Handler that gets information back from the BluetoothChatService
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_STATE_CHANGE:
                //if(D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                switch (msg.arg1) {
                case DirectMessageSession.STATE_CONNECTED:
                   
                	//once connected, send message, then wait for response
                	String address = msg.getData().getString("address");
                	ArrayList<DirectMessage> listSent = new ArrayList<DirectMessage>();
                	
                	if (address != null)
                	{
                		synchronized (mQueuedDirectMessage)
                		{
	                		Iterator<DirectMessage> itDm = mQueuedDirectMessage.iterator();
	                		while (itDm.hasNext())
	                		{
	                			DirectMessage dm = itDm.next();
	                			
	                			if (dm.to.equals(address))
	                			{
	                				String dmText = dm.body + '\n';
	                				mDirectChatSession.write(dmText.getBytes());
	                				dm.delivered = true;
	                				mStatusAdapter.notifyDataSetChanged();
	                				listSent.add(dm);
	                			}
	                		}
                		}
                		
                		
                	}
                	
                	mQueuedDirectMessage.removeAll(listSent);
                	
                    break;
                case DirectMessageSession.STATE_CONNECTING:
                  //  setStatus(R.string.title_connecting);
                    break;
                case DirectMessageSession.STATE_LISTEN:
                case DirectMessageSession.STATE_NONE:
                //    setStatus(getString(R.string.broadcast_mode_public_) + " | " + getString(R.string.you_are_) + mLocalAddress);
                    break;
                }
                break;
            case MESSAGE_WRITE:
            	
            	//we just add it directly, but we should mark as delivered here
            	/**
                byte[] writeBuf = (byte[]) msg.obj;
                // construct a string from the buffer
                String writeMessage = new String(writeBuf);
                
                Status status = new Status();
                status.from = getString(R.string.me_);
                status.body = writeMessage;
                status.trusted = true;
                status.type = Status.TYPE_DIRECT;
                status.ts = new java.util.Date().getTime();
                **/
            //    mStatusAdapter.add(status);
                break;
            case MESSAGE_READ:
                byte[] readBuf = (byte[]) msg.obj;
                // construct a string from the valid bytes in the buffer
                String readMessage = new String(readBuf, 0, msg.arg1);
                String addr = msg.getData().getString("address");
                
                StringTokenizer st = new StringTokenizer (readMessage,"\n");
                
                while (st.hasMoreTokens())
                {
	                DirectMessage dm = new DirectMessage();
	                dm.from = addr;
	                dm.body = st.nextToken();
	                dm.trusted = true;
	                dm.ts = new java.util.Date().getTime();
	                
	                sendNotitication("DM FROM: " + addr, dm.body);
	                
	                mStatusAdapter.add(dm);
                }
                
                break;
            case MESSAGE_DEVICE_NAME:
                // save the connected device's name
         //       mConnectedDeviceName = mapToNickname(msg.getData().getString(DEVICE_NAME));
               // Toast.makeText(getApplicationContext(), R.string.connected_to_
                 //              + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                break;
            case MESSAGE_TOAST:
               // Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),
                 //              Toast.LENGTH_SHORT).show();
                break;
            }
        }
    };
    
    public void sendNotitication (String title, String message)
    {
    	Notification.Builder builder =
    		    new Notification.Builder(this)
    		    .setSmallIcon(R.drawable.ic_notify)
    		    .setContentTitle(title)
    		    .setContentText(message);
    	
    	  //Vibration
        builder.setVibrate(new long[] { 500, 1000, 500 });
        builder.setAutoCancel(true);

     //LED
        builder.setLights(Color.BLUE, 3000, 3000);
        
    	Intent resultIntent = new Intent(this, GilgaMeshActivity.class);
    	
    	// Because clicking the notification opens a new ("special") activity, there's
    	// no need to create an artificial back stack.
    	PendingIntent resultPendingIntent =
    	    PendingIntent.getActivity(
    	    this,
    	    0,
    	    resultIntent,
    	    PendingIntent.FLAG_UPDATE_CURRENT
    	);
    	
    	builder.setContentIntent(resultPendingIntent);

    	// Sets an ID for the notification
    	int mNotificationId = 001;
    	// Gets an instance of the NotificationManager service
    	NotificationManager mNotifyMgr = 
    	        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    	// Builds the notification and issues it.
    	mNotifyMgr.notify(mNotificationId, builder.getNotification());
    }
    

    // Message types sent from the BluetoothChatService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;
  
}
