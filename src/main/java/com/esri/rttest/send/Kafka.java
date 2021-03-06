/*
 * (C) Copyright 2017 David Jennings
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     David Jennings
 */


package com.esri.rttest.send;

import com.esri.rttest.MarathonInfo;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Properties;
import java.util.UUID;

import com.sun.org.apache.xpath.internal.operations.Bool;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/*
 * Sends lines of a text file to a Kafka Topic 
 * Lines are sent at a specified rate.
 * 
 * @author david
 */
public class Kafka {

    private static final Logger LOG = LogManager.getLogger(Kafka.class);
    
    private Producer<String, String> producer;
    private String topic;
    
    public Kafka(String brokers, String topic) {
        
        

            // https://kafka.apache.org/documentation/#producerconfigs
            Properties props = new Properties();
            props.put("bootstrap.servers",brokers);
            props.put("client.id", Kafka.class.getName());
            props.put("acks", "1");
            props.put("retries", 0);
            props.put("batch.size", 16384);
            props.put("linger.ms", 1);
            props.put("buffer.memory", 8192000);
            props.put("request.timeout.ms", "11000");
            props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            /* Addin Simple Partioner didn't help */
            //props.put("partitioner.class", SimplePartitioner.class.getCanonicalName());
            
            this.producer = new KafkaProducer<>(props);
            this.topic = topic;
            
    }
    
    /**
     * 
     * @param filename File with lines of data to be sent.
     * @param rate Rate in lines per second to send.
     * @param numToSend Number of lines to send. If more than number of lines in file will resend from start.
     * @param burstDelay Number of milliseconds to burst at; set to 0 to send one line at a time
     */
    public long sendFile(String filename, Integer rate, long numToSend, Integer burstDelay, long startingCount) {
        try {
            FileReader fr = new FileReader(filename);
            BufferedReader br = new BufferedReader(fr);
            
            // Read the file into an array
            ArrayList<String> lines = new ArrayList<>();
            
            
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);                
            }
            
            br.close();
            fr.close();
            
            Iterator<String> linesIt = lines.iterator();
            
            // Get the System Time
            Long st = System.currentTimeMillis();

            long cnt = startingCount;
            
            // Tweak used to adjust delays to try and get requested rate
            Long tweak = 0L;              
            
            /*
                For rates < 100/s burst is better
                For rates > 100/s continous is better            
            */
            
            
            // *********** SEND Constant Rate using nanosecond delay *********
            if (burstDelay == 0) {
                // Delay between each send in nano seconds            
                Double ns_delay = 1000000000.0 / (double) rate;
                
                // By adding some to the delay you can fine tune to achieve the desired output
                ns_delay = ns_delay - (tweak * 100);
                
                long ns = ns_delay.longValue();
                if (ns < 0) ns = 0;  // can't be less than 0 


                

                while (cnt < numToSend || numToSend < 0) {
                    
                   if (cnt % rate == 0 && cnt > 0) {
                        // Calculate rate and adjust as needed
                        Double curRate = (double) cnt / (System.currentTimeMillis() - st) * 1000;

                        System.out.println(cnt + "," + String.format("%.0f", curRate));
                    }

                    if (cnt % 1000 == 0 && cnt > 0) {
                        // Calculate rate and adjust as needed
                        Double curRate = (double) cnt / (System.currentTimeMillis() - st) * 1000;

                        // rate difference as percentage 
                        Double rateDiff = (rate - curRate) / rate;

                        // Add or subracts up to 100ns 
                        tweak = (long) (rateDiff * rate);

                        // By adding some to the delay you can fine tune to achieve the desired output
                        ns = ns - tweak;
                        if (ns < 0) {
                            ns = 0;  // can't be less than 0 
                        }

                    }                             
                    
                    if (cnt == Integer.MAX_VALUE) {
                        cnt = 0;
                        st = System.currentTimeMillis();
                    }
                    
                    cnt += 1;

                    if (!linesIt.hasNext()) linesIt = lines.iterator();  // Reset Iterator

                    line = linesIt.next() + "\n";

                    final long stime = System.nanoTime();
                    
                    UUID uuid = UUID.randomUUID();
                    producer.send(new ProducerRecord<>(this.topic, uuid.toString(),line));                                        

                    
                    long etime;
                    do {
                        // This approach uses a lot of CPU                    
                        etime = System.nanoTime();
                        // Adding the following sleep for a few microsecond reduces the load
                        // However, it also effects the through put
                        //Thread.sleep(0,100);  
                    } while (stime + ns >= etime);                

                }
            } else {
                // *********** SEND in bursts every msDelay ms  *********

                Integer msDelay = burstDelay;
                Integer numPerBurst = Math.round(rate / 1000 * msDelay); 

                if (numPerBurst < 1) numPerBurst = 1;
                
                Integer delay = burstDelay;

                while (cnt < numToSend || numToSend < 0) {
                    
                   // Adjust delay every burst
                    Double curRate = (double) cnt / (System.currentTimeMillis() - st) * 1000;
                    Double rateDiff = (rate - curRate) / rate;
                    tweak = (long) (rateDiff * rate);
                    delay = delay - Math.round(tweak / 1000.0f);
                    if (delay < 0) {
                        delay = 0;  // delay cannot be negative
                    } else {
                        Thread.sleep(delay);
                    }                    
                                        
                                        

                    Integer i = 0;
                    while (i < numPerBurst) {
                        if (cnt % rate == 0 && cnt > 0) {
                            curRate = (double) cnt / (System.currentTimeMillis() - st) * 1000;
                            System.out.println(cnt + "," + String.format("%.0f", curRate));
                        }                        
                        
                        if (cnt == Integer.MAX_VALUE) {
                            cnt = 0;
                            st = System.currentTimeMillis();
                            break;
                        }                        
                        
                        cnt += 1;
                        
                        i += 1;
                        if (!linesIt.hasNext()) linesIt = lines.iterator();  // Reset Iterator

                        line = linesIt.next() + "\n";

                        UUID uuid = UUID.randomUUID();
                        producer.send(new ProducerRecord<>(this.topic, uuid.toString(),line));
                        
                        // Break out as soon as numToSend is reached
                        if (cnt >= numToSend) {
                            break;
                        }                           
                        
                    }

                }
            
            }            
            
            // This command was needed when running multiple instances of Kafka; otherwise, lines were lost
            producer.flush();
            
            Double sendRate = (double) cnt / (System.currentTimeMillis() - st) * 1000;
            
            System.out.println(cnt + "," + String.format("%.0f", sendRate));

            return cnt;
                             
        } catch (IOException | InterruptedException e) {
            // Could fail on very large files that would fill heap space 
            
            LOG.error("ERROR", e);
            
        }
        return startingCount;
    }

  /**
   *
   * @param path Path to a file or directory, with one or more files, with lines of data to be sent.
   * @param rate Rate in lines per second to send.
   * @param numToSend Number of lines to send. If more than number of lines in file will resend from start.
   * @param burstDelay Number of milliseconds to burst at; set to 0 to send one line at a time
   */
  public void sendFiles(String path, Integer rate, long numToSend, Integer burstDelay) {
    try {

      File inputPath = new File(path);

      if(inputPath.isDirectory()) {

        File[] listOfFiles = inputPath.listFiles();
        Arrays.sort(listOfFiles);

        long count = 0;
        for (int i = 0; i < listOfFiles.length && count < numToSend; i++) {
          if (listOfFiles[i].isFile()) {
            count = sendFile(listOfFiles[i].getAbsolutePath(), rate, numToSend, burstDelay, count);
          }
        }
      }
      else{
        sendFile(inputPath.getAbsolutePath(), rate, numToSend, burstDelay, 0);
      }

    } catch (Exception e) {
      // Could fail on very large files that would fill heap space

      LOG.error("ERROR", e);

    }
  }
    
    
    
    public static void main(String args[]) throws Exception {
        
        // Command Line d1.trinity.dev:9092 simFile simFile_1000_10s.dat 1000 10000
        
        if (args.length != 5 && args.length != 6) {
            System.err.print("Usage: Kafka <broker-list-or-hub-name> <topic> <file> <rate> <numrecords> (<burst-delay-ms>)\n");
        } else {
            
            String brokers = args[0];
            
            String brokerSplit[] = brokers.split(":");
            
            if (brokerSplit.length == 1) {
                // Try hub name. Name cannot have a ':' and brokers must have it.
                brokers = new MarathonInfo().getBrokers(brokers);
            }   // Otherwise assume it's brokers 

            Kafka t = new Kafka(brokers, args[1]);
            if (args.length == 5) {
              t.sendFiles(args[2], Integer.parseInt(args[3]), Long.parseLong(args[4]), 0);
            } else {
              t.sendFiles(args[2], Integer.parseInt(args[3]), Long.parseLong(args[4]), Integer.parseInt(args[5]));
            }

        }

    }
}
