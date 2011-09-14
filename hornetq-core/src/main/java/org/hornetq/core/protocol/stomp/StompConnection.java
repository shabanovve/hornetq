/*
 * Copyright 2010 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.core.protocol.stomp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hornetq.api.core.HornetQBuffer;
import org.hornetq.api.core.HornetQBuffers;
import org.hornetq.api.core.HornetQException;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.remoting.CloseListener;
import org.hornetq.core.remoting.FailureListener;
import org.hornetq.core.server.ServerMessage;
import org.hornetq.core.server.impl.ServerMessageImpl;
import org.hornetq.spi.core.protocol.RemotingConnection;
import org.hornetq.spi.core.remoting.Connection;

/**
 * A StompConnection
 *
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 *
 *
 */
public class StompConnection implements RemotingConnection
{
   private static final Logger log = Logger.getLogger(StompConnection.class);
   
   protected static final String CONNECTION_ID_PROP = "__HQ_CID";

   private final StompProtocolManager manager;

   private final Connection transportConnection;

   private String login;

   private String passcode;

   private String clientID;

   //this means login is valid. (stomp connection ok)
   private boolean valid;

   private boolean destroyed = false;
   
   private final long creationTime;

   private StompDecoder decoder;

   private final List<FailureListener> failureListeners = new CopyOnWriteArrayList<FailureListener>();

   private final List<CloseListener> closeListeners = new CopyOnWriteArrayList<CloseListener>();

   private final Object failLock = new Object();
   
   private volatile boolean dataReceived;
   
   private StompVersions version;
   
   private VersionedStompFrameHandler frameHandler;
   
   //this means the version negotiation done.
   private boolean initialized;
   
   private FrameEventListener stompListener;

   public StompDecoder getDecoder()
   {
      return decoder;
   }

   StompConnection(final Connection transportConnection, final StompProtocolManager manager)
   {
      this.transportConnection = transportConnection;

      this.manager = manager;
      
      this.decoder = new StompDecoder(this);
      
      this.creationTime = System.currentTimeMillis();
   }

   public void addFailureListener(final FailureListener listener)
   {
      if (listener == null)
      {
         throw new IllegalStateException("FailureListener cannot be null");
      }

      failureListeners.add(listener);
   }

   public boolean removeFailureListener(final FailureListener listener)
   {
      if (listener == null)
      {
         throw new IllegalStateException("FailureListener cannot be null");
      }

      return failureListeners.remove(listener);
   }

   public void addCloseListener(final CloseListener listener)
   {
      if (listener == null)
      {
         throw new IllegalStateException("CloseListener cannot be null");
      }

      closeListeners.add(listener);
   }

   public boolean removeCloseListener(final CloseListener listener)
   {
      if (listener == null)
      {
         throw new IllegalStateException("CloseListener cannot be null");
      }

      return closeListeners.remove(listener);
   }

   public List<CloseListener> removeCloseListeners()
   {
      List<CloseListener> ret = new ArrayList<CloseListener>(closeListeners);

      closeListeners.clear();

      return ret;
   }

   public List<FailureListener> removeFailureListeners()
   {
      List<FailureListener> ret = new ArrayList<FailureListener>(failureListeners);

      failureListeners.clear();

      return ret;
   }

   public void setCloseListeners(List<CloseListener> listeners)
   {
      closeListeners.clear();

      closeListeners.addAll(listeners);
   }

   public void setFailureListeners(final List<FailureListener> listeners)
   {
      failureListeners.clear();

      failureListeners.addAll(listeners);
   }
   
   public void setDataReceived()
   {
      dataReceived = true;
   }

   public boolean checkDataReceived()
   {
      boolean res = dataReceived;

      dataReceived = false;

      return res;
   }

   public HornetQBuffer createBuffer(int size)
   {
      return HornetQBuffers.dynamicBuffer(size);
   }

   public void destroy()
   {
      synchronized (failLock)
      {
         if (destroyed)
         {
            return;
         }
      }

      destroyed = true;

      internalClose();

      callClosingListeners();
   }

   private void internalClose()
   {
      transportConnection.close();

      manager.cleanup(this);
   }

   public void fail(final HornetQException me)
   {
      synchronized (failLock)
      {
         if (destroyed)
         {
            return;
         }

         destroyed = true;
      }

      log.warn("Connection failure has been detected: " + me.getMessage() +
                                      " [code=" +
                                      me.getCode() +
                                      "]");

      // Then call the listeners
      callFailureListeners(me);

      callClosingListeners();
      
      internalClose();
   }

   public void flush()
   {
   }

   public List<FailureListener> getFailureListeners()
   {
      // we do not return the listeners otherwise the remoting service
      // would NOT destroy the connection.
      return Collections.emptyList();
   }

   public Object getID()
   {
      return transportConnection.getID();
   }

   public String getRemoteAddress()
   {
      return transportConnection.getRemoteAddress();
   }
   
   public long getCreationTime()
   {
      return creationTime;
   }

   public Connection getTransportConnection()
   {
      return transportConnection;
   }

   public boolean isClient()
   {
      return false;
   }

   public boolean isDestroyed()
   {
      return destroyed;
   }

   public void bufferReceived(Object connectionID, HornetQBuffer buffer)
   {
      manager.handleBuffer(this, buffer);
   }

   public void setLogin(String login)
   {
      this.login = login;
   }

   public String getLogin()
   {
      return login;
   }

   public void setPasscode(String passcode)
   {
      this.passcode = passcode;
   }

   public String getPasscode()
   {
      return passcode;
   }

   public void setClientID(String clientID)
   {
      this.clientID = clientID;
   }

   public String getClientID()
   {
      return clientID;
   }

   public boolean isValid()
   {
      return valid;
   }

   public void setValid(boolean valid)
   {
      this.valid = valid;
   }

   private void callFailureListeners(final HornetQException me)
   {
      final List<FailureListener> listenersClone = new ArrayList<FailureListener>(failureListeners);

      for (final FailureListener listener : listenersClone)
      {
         try
         {
            listener.connectionFailed(me, false);
         }
         catch (final Throwable t)
         {
            // Failure of one listener to execute shouldn't prevent others
            // from
            // executing
            log.error("Failed to execute failure listener", t);
         }
      }
   }

   private void callClosingListeners()
   {
      final List<CloseListener> listenersClone = new ArrayList<CloseListener>(closeListeners);

      for (final CloseListener listener : listenersClone)
      {
         try
         {
            listener.connectionClosed();
         }
         catch (final Throwable t)
         {
            // Failure of one listener to execute shouldn't prevent others
            // from
            // executing
            log.error("Failed to execute failure listener", t);
         }
      }
   }

   /*
    * accept-version value takes form of "v1,v2,v3..."
    * we need to return the highest supported version
    */
   public void negotiateVersion(StompFrame frame) throws HornetQStompException
   {
      String acceptVersion = frame.getHeader(Stomp.Headers.ACCEPT_VERSION);
      
      log.error("----------------- acceptVersion: " + acceptVersion);
      
      if (acceptVersion == null)
      {
         this.version = StompVersions.V1_0;
      }
      else
      {
         Set<String> requestVersions = new HashSet<String>();
         StringTokenizer tokenizer = new StringTokenizer(acceptVersion, ",");
         while (tokenizer.hasMoreTokens())
         {
            requestVersions.add(tokenizer.nextToken());
         }
         
         if (requestVersions.contains("1.1"))
         {
            this.version = StompVersions.V1_1;
         }
         else if (requestVersions.contains("1.0"))
         {
            this.version = StompVersions.V1_0;
         }
         else
         {
            //not a supported version!
            HornetQStompException error = new HornetQStompException("Stomp versions not supported: " + acceptVersion);
            error.addHeader("version", acceptVersion);
            error.addHeader("content-type", "text/plain");
            error.setBody("Supported protocol version are " + manager.getSupportedVersionsAsString());
            throw error;
         }
         log.error("------------------ negotiated version is " + this.version);
      }
      
      this.frameHandler = VersionedStompFrameHandler.getHandler(this, this.version);
      this.initialized = true;
   }

   //reject if the host doesn't match
   public void setHost(String host) throws HornetQStompException
   {
      if (host == null)
      {
         HornetQStompException error = new HornetQStompException("Header host is null");
         error.setBody("Cannot accept null as host");
         throw error;
      }
      
      String localHost = manager.getVirtualHostName();
      if (!host.equals(localHost))
      {
         HornetQStompException error = new HornetQStompException("Header host doesn't match server host");
         error.setBody("host " + host + " doesn't match server host name");
         throw error;
      }
   }

   public void handleFrame(StompFrame request)
   {
      StompFrame reply = null;
      
      if (stompListener != null)
      {
         stompListener.requestAccepted(request);
      }

      try
      {
         if (!initialized)
         {
            String cmd = request.getCommand();
            if ( ! (Stomp.Commands.CONNECT.equals(cmd) || Stomp.Commands.STOMP.equals(cmd)))
            {
               throw new HornetQStompException("Connection hasn't been established.");
            }
            //decide version
            negotiateVersion(request);
         }
         reply = frameHandler.handleFrame(request);
      }
      catch (HornetQStompException e)
      {
         reply = e.getFrame();
      }
      
      if (reply != null)
      {
         sendFrame(reply);
      }
   }

   public void sendFrame(StompFrame frame)
   {
      log.error("--------------- sending reply: " + frame);
      manager.sendReply(this, frame);
   }

   public boolean validateUser(String login, String passcode)
   {
      this.valid = manager.validateUser(login, passcode);
      if (valid)
      {
         this.login = login;
         this.passcode = passcode;
      }
      return valid;
   }

   public ServerMessageImpl createServerMessage()
   {
      return manager.createServerMessage();
   }

   public StompSession getSession(String txID) throws HornetQStompException
   {
      StompSession session = null;
      try
      {
         if (txID == null)
         {
            session = manager.getSession(this);
         }
         else
         {
            session = manager.getTransactedSession(this, txID);
         }
      }
      catch (Exception e)
      {
         throw new HornetQStompException("Exception getting session", e);
      }
      
      return session;
   }

   public void validate() throws HornetQStompException
   {
      if (!this.valid)
      {
         throw new HornetQStompException("Connection is not valid.");
      }
   }

   public void sendServerMessage(ServerMessageImpl message, String txID) throws HornetQStompException
   {
      StompSession stompSession = getSession(txID);

      if (stompSession.isNoLocal())
      {
         message.putStringProperty(CONNECTION_ID_PROP, getID().toString());
      }
      try
      {
         log.error("--------------------- sending mesage: " + message);
         stompSession.getSession().send(message, true);
         log.error("----------------------sent by " + stompSession.getSession());
      }
      catch (Exception e)
      {
         throw new HornetQStompException("Error sending message " + message, e);
      }
   }

   @Override
   public void disconnect()
   {
      destroy();
   }

   public void beginTransaction(String txID) throws HornetQStompException
   {
      try
      {
         manager.beginTransaction(this, txID);
      }
      catch (HornetQStompException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new HornetQStompException("Error beginning a transaction: " + txID, e);
      }
   }

   public void commitTransaction(String txID) throws HornetQStompException
   {
      try
      {
         manager.commitTransaction(this, txID);
      }
      catch (Exception e)
      {
         throw new HornetQStompException("Error committing " + txID, e);
      }
   }

   public void abortTransaction(String txID) throws HornetQStompException
   {
      try
      {
         manager.abortTransaction(this, txID);
      }
      catch (HornetQStompException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new HornetQStompException("Error aborting " + txID, e);
      }
   }

   public void subscribe(String destination, String selector, String ack,
         String id, String durableSubscriptionName, boolean noLocal) throws HornetQStompException
   {
      if (noLocal)
      {
         String noLocalFilter = CONNECTION_ID_PROP + " <> '" + getID().toString() + "'";
         if (selector == null)
         {
            selector = noLocalFilter;
         }
         else
         {
            selector += " AND " + noLocalFilter;
         }
      }
      if (ack == null)
      {
         ack = Stomp.Headers.Subscribe.AckModeValues.AUTO;
      }

      String subscriptionID = null;
      if (id != null)
      {
         subscriptionID = id;
      }
      else
      {
         if (destination == null)
         {
            throw new HornetQStompException("Client must set destination or id header to a SUBSCRIBE command");
         }
         subscriptionID = "subscription/" + destination;
      }
      
      try
      {
         manager.createSubscription(this, subscriptionID, durableSubscriptionName, destination, selector, ack, noLocal);
      }
      catch (HornetQStompException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new HornetQStompException("Error creating subscription " + subscriptionID, e);
      }
   }

   public void unsubscribe(String subscriptionID) throws HornetQStompException
   {
      try
      {
         manager.unsubscribe(this, subscriptionID);
      }
      catch (HornetQStompException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new HornetQStompException("Error unsubscripting " + subscriptionID, e);
      }
   }

   public void acknowledge(String messageID, String subscriptionID) throws HornetQStompException
   {
      try
      {
         manager.acknowledge(this, messageID, subscriptionID);
      }
      catch (HornetQStompException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new HornetQStompException("Error acknowledging message " + messageID, e);
      }
   }

   public String getVersion()
   {
      return String.valueOf(version);
   }

   public String getHornetQServerName()
   {
      //hard coded, review later.
      return "HornetQ/2.2.5 HornetQ Messaging Engine";
   }

   public StompFrame createStompMessage(ServerMessage serverMessage,
         StompSubscription subscription, int deliveryCount) throws Exception
   {
      return frameHandler.createMessageFrame(serverMessage, subscription, deliveryCount);
   }

   public void addStompEventListener(FrameEventListener listener)
   {
      this.stompListener = listener;
   }

   //send a ping stomp frame
   public void ping(StompFrame pingFrame)
   {
      manager.sendReply(this, pingFrame);
   }

   public void physicalSend(StompFrame frame) throws Exception
   {
      HornetQBuffer buffer = frame.toHornetQBuffer();
      getTransportConnection().write(buffer, false, false);

      if (stompListener != null)
      {
         stompListener.replySent(frame);
      }

   }

   public VersionedStompFrameHandler getFrameHandler()
   {
      return this.frameHandler;
   }
}
