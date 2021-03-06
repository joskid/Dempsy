/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nokia.dempsy.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.nokia.dempsy.Dispatcher;
import com.nokia.dempsy.annotations.Activation;
import com.nokia.dempsy.annotations.Evictable;
import com.nokia.dempsy.annotations.MessageHandler;
import com.nokia.dempsy.annotations.MessageProcessor;
import com.nokia.dempsy.annotations.Output;
import com.nokia.dempsy.container.mocks.ContainerTestMessage;
import com.nokia.dempsy.container.mocks.OutputMessage;
import com.nokia.dempsy.messagetransport.Sender;
import com.nokia.dempsy.serialization.Serializer;
import com.nokia.dempsy.serialization.java.JavaSerializer;


//
// NOTE: this test simply puts messages on an input queue, and expects
//       messages on an output queue; the important part is the wiring
//       in TestMPContainer.xml
//
public class TestMpContainer
{
//----------------------------------------------------------------------------
//  Configuration
//----------------------------------------------------------------------------

   private MpContainer container;
   private BlockingQueue<Object> inputQueue;
   private BlockingQueue<Object> outputQueue;
   private Serializer<Object> serializer = new JavaSerializer<Object>();

   private ClassPathXmlApplicationContext context;

   public static class DummyDispatcher implements Dispatcher
   {
      public Object lastDispatched;
      
      public Sender sender;
      
      public Serializer<Object> serializer = new JavaSerializer<Object>();

      @Override
      public void dispatch(Object message)
      {
         this.lastDispatched = message;
         try
         {
            sender.send(serializer.serialize(message));
         }
         catch(Exception e)
         {
            System.out.println("FAILED!");
            e.printStackTrace();
            throw new RuntimeException(e);
         }
      }
      
      public void setSender(Sender sender)
      {
         this.sender = sender;
      }
   }


   @SuppressWarnings("unchecked")
   @Before
   public void setUp()
   throws Exception
   {
      context = new ClassPathXmlApplicationContext("TestMPContainer.xml");
      container = (MpContainer)context.getBean("container");
      assertNotNull(container.getSerializer());
      inputQueue = (BlockingQueue<Object>)context.getBean("inputQueue");
      outputQueue = (BlockingQueue<Object>)context.getBean("outputQueue");
   }


   @After
   public void tearDown()
   throws Exception
   {
      container.shutdown();
      context.close();
      context.destroy();
      context = null;
   }


//----------------------------------------------------------------------------
//  Message and MP classes
//----------------------------------------------------------------------------



   @MessageProcessor
   public static class TestProcessor implements Cloneable
   {
      public volatile String myKey;
      public volatile int activationCount;
      public volatile int invocationCount;
      public volatile int outputCount;
      public volatile boolean evict = false;
      public static int cloneCount;

      @Override
      public TestProcessor clone()
      throws CloneNotSupportedException
      {
         cloneCount++;
         return (TestProcessor)super.clone();
      }

      @Activation
      public void activate(byte[] data)
      {
         activationCount++;
      }

      @MessageHandler
      public ContainerTestMessage handle(ContainerTestMessage message)
      {
         myKey = message.getKey();
         invocationCount++;

         // put it on output queue so that we can synchronize test
         return message;
      }
      
      @Evictable
      public boolean isEvictable(){ return evict; }

      @Output
      public OutputMessage doOutput()
      {
         return new OutputMessage(myKey, activationCount, invocationCount, outputCount++);
      }
   }


//----------------------------------------------------------------------------
//  Test Cases
//----------------------------------------------------------------------------

   @Test
   public void testConfiguration()
   throws Exception
   {
      // this assertion is superfluous, since we deref container in setUp()
      assertNotNull("did not create container", container);
   }


   @Test
   public void testMessageDispatch()
   throws Exception
   {
      inputQueue.add(serializer.serialize(new ContainerTestMessage("foo")));
      outputQueue.poll(1000, TimeUnit.MILLISECONDS);

      assertEquals("did not create MP", 1, container.getProcessorCount());

      TestProcessor mp = (TestProcessor)container.getMessageProcessor("foo");
      assertNotNull("MP not associated with expected key", mp);
      assertEquals("activation count, 1st message", 1, mp.activationCount);
      assertEquals("invocation count, 1st message", 1, mp.invocationCount);

      inputQueue.add(serializer.serialize(new ContainerTestMessage("foo")));
      outputQueue.poll(1000, TimeUnit.MILLISECONDS);

      assertEquals("activation count, 2nd message", 1, mp.activationCount);
      assertEquals("invocation count, 2nd message", 2, mp.invocationCount);
   }


   @Test
   public void testInvokeOutput()
   throws Exception
   {
      inputQueue.add(serializer.serialize(new ContainerTestMessage("foo")));
      outputQueue.poll(1000, TimeUnit.MILLISECONDS);
      inputQueue.add(serializer.serialize(new ContainerTestMessage("bar")));
      outputQueue.poll(1000, TimeUnit.MILLISECONDS);

      assertEquals("number of MP instances", 2, container.getProcessorCount());
      assertTrue("queue is empty", outputQueue.isEmpty());

      container.outputPass();
      OutputMessage out1 = (OutputMessage)serializer.deserialize((byte[]) outputQueue.poll(1000, TimeUnit.MILLISECONDS));
      OutputMessage out2 = (OutputMessage)serializer.deserialize((byte[]) outputQueue.poll(1000, TimeUnit.MILLISECONDS));

      assertTrue("messages received", (out1 != null) && (out2 != null));
      assertEquals("no more messages in queue", 0, outputQueue.size());

      // order of messages is not guaranteed, so we need to aggregate keys
      HashSet<String> messageKeys = new HashSet<String>();
      messageKeys.add(out1.getKey());
      messageKeys.add(out2.getKey());
      assertTrue("first MP sent output", messageKeys.contains("foo"));
      assertTrue("second MP sent output", messageKeys.contains("bar"));
   }

   @Test
   public void testInvokeOutputScheduler()
   throws Exception
   {
      inputQueue.add(serializer.serialize(new ContainerTestMessage("foo")));
      ContainerTestMessage out1 = (ContainerTestMessage)serializer.deserialize((byte[]) outputQueue.poll(1000, TimeUnit.MILLISECONDS));
      assertTrue("messages received", (out1 != null) );

      assertEquals("number of MP instances", 1, container.getProcessorCount());
      assertTrue("queue is empty", outputQueue.isEmpty());

      container.setOutPutPass(100, TimeUnit.MILLISECONDS);

      OutputMessage out2 = null;
      for(int i=0; i<10; i++)
      {
         byte[] out = (byte[]) outputQueue.poll(10000, TimeUnit.MILLISECONDS);
         assertNotNull(out);
         out2 = (OutputMessage)serializer.deserialize(out);
         Assert.assertNotNull(out2);
      }
      Assert.assertTrue(out2.outputCount>8);
   }

   @Test
   public void testOutputInvoker() throws Exception {
	   inputQueue.add(serializer.serialize(new ContainerTestMessage("foo")));
	      ContainerTestMessage out1 = (ContainerTestMessage)serializer.deserialize((byte[]) outputQueue.poll(1000, TimeUnit.MILLISECONDS));
	      assertTrue("messages received", (out1 != null) );

	      assertEquals("number of MP instances", 1, container.getProcessorCount());
	      assertTrue("queue is empty", outputQueue.isEmpty());

   }
   
   @Test
   public void testEvictable()
   throws Exception
   {
      inputQueue.add(serializer.serialize(new ContainerTestMessage("foo")));
      outputQueue.poll(1000, TimeUnit.MILLISECONDS);

      assertEquals("did not create MP", 1, container.getProcessorCount());

      TestProcessor mp = (TestProcessor)container.getMessageProcessor("foo");
      assertNotNull("MP not associated with expected key", mp);
      assertEquals("activation count, 1st message", 1, mp.activationCount);
      assertEquals("invocation count, 1st message", 1, mp.invocationCount);

      inputQueue.add(serializer.serialize(new ContainerTestMessage("foo")));
      outputQueue.poll(1000, TimeUnit.MILLISECONDS);

      assertEquals("activation count, 2nd message", 1, mp.activationCount);
      assertEquals("invocation count, 2nd message", 2, mp.invocationCount);
      int tmpCloneCount = TestProcessor.cloneCount;
      
      mp.evict = true;
      inputQueue.add(serializer.serialize(new ContainerTestMessage("foo")));
      outputQueue.poll(1000, TimeUnit.MILLISECONDS);

      assertEquals("Clone count, 2nd message", tmpCloneCount+1, TestProcessor.cloneCount);

   }

}
