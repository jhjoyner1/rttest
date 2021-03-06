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
/**
 * Test Class
 */
package com.esri.rttest.send;

import com.esri.rttest.MarathonInfo;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.json.JSONObject;

/**
 *
 * @author david
 */
public class ElasticsearchTrans {

    private static final Logger LOG = LogManager.getLogger(ElasticsearchTrans.class);
    
    String esnodes;
    String clusterName;
    String idx;
    String typ;
    Integer esbulk;
    Client client;

    public ElasticsearchTrans(String esnodes, String clusterName, String idx, String typ, Integer esbulk) {

        try {

            this.esnodes = esnodes;
            this.clusterName = clusterName;
            this.idx = idx;
            this.typ = typ;
            this.esbulk = esbulk;

//            // These are for Elasticsearch 2.x
//            Settings settings = Settings.settingsBuilder().put("cluster.name", this.clusterName).build();
//            TransportClient tc = TransportClient.builder().settings(settings).build();
            // These are for Elasticsearch 5
            Settings settings = Settings.builder().put("cluster.name", this.clusterName).build();
            TransportClient tc = new PreBuiltTransportClient(settings);

            String hosts[] = esnodes.split(",");
            for (String host : hosts) {
                String parts[] = host.split(":");
                InetAddress addr = InetAddress.getByName(parts[0]);
                Integer port = Integer.parseInt(parts[1]);

                tc.addTransportAddress(new TransportAddress(addr, port));

            }

            this.client = (Client) tc;

        } catch (NumberFormatException | UnknownHostException e) {
            LOG.error("ERROR",e);
        }
    }

    public void sendFile(String filename, Integer rate, Integer numToSend) {

        ArrayList<String> lines = new ArrayList<String>();

        try {
            FileReader fr = new FileReader(filename);
            BufferedReader br = new BufferedReader(fr);

            // Read the file into an array
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }

            br.close();
            fr.close();
        } catch (IOException e) {
            LOG.error("ERROR",e);
        }

        BulkProcessor bulkProcessor = BulkProcessor.builder(
                client,
                new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId,
                    BulkRequest request) {
            }

            @Override
            public void afterBulk(long executionId,
                    BulkRequest request,
                    BulkResponse response) {
            }

            @Override
            public void afterBulk(long executionId,
                    BulkRequest request,
                    Throwable failure) {
                if (failure != null) {
                    String ms = failure.getMessage();
                    if (!ms.isEmpty()) {
                        System.out.println(ms);
                    }
                }

            }
        })
                .setBulkActions(this.esbulk)
                .setBulkSize(new ByteSizeValue(1, ByteSizeUnit.GB))
                .setFlushInterval(TimeValue.timeValueSeconds(5))
                .setConcurrentRequests(1)
                .build();

        Iterator<String> linesIt = lines.iterator();

        // Get the System Time
        LocalDateTime st = LocalDateTime.now();

        Integer cnt = 0;

        // Delay between each send in nano seconds            
        Double ns_delay = 1000000000.0 / (double) rate;

        long ns = ns_delay.longValue();
        if (ns < 0) {
            ns = 0;  // can't be less than 0 
        }

        while (cnt < numToSend) {
            cnt += 1;
            final long stime = System.nanoTime();

            if (!linesIt.hasNext()) {
                linesIt = lines.iterator();  // Reset Iterator
            }
            String line = linesIt.next();

            JSONObject json = new JSONObject(lines);

            Map<String, Object> map = new HashMap<>();
            Iterator keys = json.keys();
            
            while (keys.hasNext()) {
                String key = (String) keys.next();
                map.put(key, json.get(key));
            }

            bulkProcessor.add(new IndexRequest(this.idx, this.typ).source(map));

            // polls every 10ms
            Long ct = System.currentTimeMillis();

            if (cnt == 1) {
                st = LocalDateTime.now();
            }

            long etime;
            do {
                // This approach uses a lot of CPU                    
                etime = System.nanoTime();
                // Adding the following sleep for a few microsecond reduces the load
                // However, it also effects the through put
                //Thread.sleep(0,100);  
            } while (stime + ns >= etime);

        }

        bulkProcessor.close();

        LocalDateTime et = LocalDateTime.now();

        Duration delta = Duration.between(st, et);

        Double elapsedSeconds = (double) delta.getSeconds() + delta.getNano() / 1000000000.0;

        double sendRate = (double) cnt / elapsedSeconds;

        System.out.println(cnt + "," + sendRate);

    }

    public static void main(String[] args) {

        /*
        curl -XPUT 192.168.0.142:1025/simulator/_settings -d '{"index":{"refresh_interval":"60s"}}'; echo
        
        {"acknowledged":true}

        
        curl -XGET 192.168.0.142:1025/simulator/_settings; echo
        
        {"simulator":{"settings":{"index":{"refresh_interval":"60s","number_of_shards":"5","provided_name":"simulator","creation_date":"1504313608065","number_of_replicas":"1","uuid":"w19PfzyTSKWxja63MdxJIw","version":{"created":"5050199"}}}}}

        1cpu data node 4,000/s
        4cpu data node 5,000/s 
        
         */
        // Command line example: a3:9300 simulator simfile simFile_1000_10s.json 100 1000 20
        // java -cp target/Simulator.jar com.esri.simulator.Elasticsearch3 http://192.168.0.142:1025/simulator/simFile simFile_1000_10s.json 9000 100000 300
        int numargs = args.length;
        if (numargs != 7 && numargs != 8) {
            System.err.print("Usage: ElasticsearchTrans <elastic-search-transports> <cluster-name> <index> <type> <file> <rate> <numrecords> (<elastic-bulk-num>)\n");
        } else {

            String transports = args[0];
            String clusterName = args[1];
            String idx = args[2];
            String typ = args[3];
            String filename = args[4];
            Integer rate = Integer.parseInt(args[5]);
            Integer numRecords = Integer.parseInt(args[6]);

            Integer elasticBulk = 1000;
            if (numargs == 8) {
                elasticBulk = Integer.parseInt(args[7]);
            }

            String esnodesSplit[] = transports.split(":");
            if (esnodesSplit.length == 1) {
                // Assume this is a MarathonName
                MarathonInfo mi = new MarathonInfo();
                if (clusterName.equalsIgnoreCase("-")) {
                    clusterName = mi.getElasticSearchClusterName(transports);
                }

                // Try hub name. Name cannot have a ':' and brokers must have it.
                transports = mi.getElasticSearchTransportAddresses(transports);

            }

//        String transports = "e2:9300";
//        String clusterName = "elasticsearch";
//        String idx = "sink";
//        String typ = "test1";
//        String filename = "simFile_1000_10s.json";
//        Integer rate = 20000;
//        Integer numRecords = 100000;
//        Integer elasticBulk = 1000;
//        System.out.println("transports: " + transports);
//        System.out.println("clusterName: " + clusterName);
//        System.out.println("idx: " + idx);
//        System.out.println("typ: " + typ);
//        System.out.println("filename: " + filename);
//        System.out.println("rate: " + rate);
//        System.out.println("numRecords: " + numRecords);
//        System.out.println("elasticBulk: " + elasticBulk);
            ElasticsearchTrans t = new ElasticsearchTrans(transports, clusterName, idx, typ, elasticBulk);
            t.sendFile(filename, rate, numRecords);

        }

    }

}
