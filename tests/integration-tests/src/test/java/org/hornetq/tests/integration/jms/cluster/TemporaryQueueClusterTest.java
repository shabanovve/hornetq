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

package org.hornetq.tests.integration.jms.cluster;
import org.junit.Before;
import org.junit.After;

import org.junit.Test;

import javax.jms.Connection;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.hornetq.tests.util.JMSClusteredTestBase;

/**
 * A TopicClusterTest
 *
 * @author <mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 *
 *
 */
public class TemporaryQueueClusterTest extends JMSClusteredTestBase
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   @After
   public void tearDown() throws Exception
   {
      super.tearDown();
   }

   @Before
   public void setUp() throws Exception
   {
      super.setUp();
   }



   @Test
   public void testClusteredQueue() throws Exception
   {
      System.out.println("Server1 = " + server1.getNodeID());
      System.out.println("Server2 = " + server2.getNodeID());
      jmsServer1.createQueue(false, "target", null, true, "/queue/target");
      jmsServer2.createQueue(false, "target", null, true, "/queue/target");

      Connection conn1 = cf1.createConnection();
      Connection conn2 = cf2.createConnection();

      conn1.start();

      conn2.start();

      try
      {
         Session session1 = conn1.createSession(false, Session.AUTO_ACKNOWLEDGE);
         Queue targetQueue1 = session1.createQueue("target");

         Session session2 = conn2.createSession(false, Session.AUTO_ACKNOWLEDGE);
         Queue targetQueue2 = session2.createQueue("target");

         // sleep a little bit to have the temp queue propagated to server #2
         Thread.sleep(3000);
         MessageProducer prod1 = session1.createProducer(targetQueue1);
         MessageConsumer cons2 = session2.createConsumer(targetQueue2);

         TextMessage msg = session1.createTextMessage("hello");

         prod1.send(msg);

         prod1.send(msg);

         TextMessage msgReceived = (TextMessage)cons2.receive(5000);

         assertNotNull(msgReceived);
         assertEquals(msgReceived.getText(), msg.getText());

      }
      finally
      {
         conn1.close();
         conn2.close();
      }
   }


   @Test
   public void testTemporaryQueue() throws Exception
   {
      jmsServer1.createQueue(false, "target", null, false, "/queue/target");
      jmsServer2.createQueue(false, "target", null, false, "/queue/target");

      Connection conn1 = cf1.createConnection();
      Connection conn2 = cf2.createConnection();

      conn1.start();
      conn2.start();

      try
      {
         Session session1 = conn1.createSession(false, Session.AUTO_ACKNOWLEDGE);
         Queue targetQueue1 = session1.createQueue("target");
         Queue tempQueue = session1.createTemporaryQueue();
         System.out.println("temp queue is " + tempQueue.getQueueName());
         Session session2 = conn2.createSession(false, Session.AUTO_ACKNOWLEDGE);
         Queue targetQueue2 = session2.createQueue("target");

         MessageProducer prod1 = session1.createProducer(targetQueue1);
         MessageConsumer cons2 = session2.createConsumer(targetQueue2);
         MessageConsumer tempCons1 = session1.createConsumer(tempQueue);
         // sleep a little bit to have the temp queue propagated to server #2
         Thread.sleep(3000);

         for (int i = 0; i < 10; i++)
         {
            TextMessage message = session1.createTextMessage("" + i);
            message.setJMSReplyTo(tempQueue);
            prod1.send(message);
         }

         for (int i = 0; i < 10; i++)
         {
            if (i % 2 == 0)
            {
               TextMessage received = (TextMessage)cons2.receive(5000);
               System.out.println(received.getText());
               System.out.println("check temp queue on server #2");
               MessageProducer tempProducer = session2.createProducer(received.getJMSReplyTo());
               tempProducer.send(session2.createTextMessage(">>> " + received.getText()));
               tempProducer.close();
            }
         }

         for (int i = 0; i < 10; i++)
         {
            if (i % 2 == 0)
            {
               TextMessage received = (TextMessage)tempCons1.receive(5000);
               System.out.println(received.getText());
            }
         }
      }
      finally
      {
         conn1.close();
         conn2.close();
      }

      jmsServer1.destroyQueue("target");
      jmsServer2.destroyQueue("target");

   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
