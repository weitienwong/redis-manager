package com.newegg.ec.redis.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.newegg.ec.redis.client.RedisClient;
import com.newegg.ec.redis.client.RedisClientFactory;
import com.newegg.ec.redis.config.RCTConfig;
import com.newegg.ec.redis.dao.IRdbAnalyze;
import com.newegg.ec.redis.dao.IRdbAnalyzeResult;
import com.newegg.ec.redis.entity.*;
import com.newegg.ec.redis.plugin.rct.cache.AppCache;
import com.newegg.ec.redis.plugin.rct.thread.AnalyzerStatusThread;
import com.newegg.ec.redis.schedule.RDBScheduleJob;
import com.newegg.ec.redis.service.IRdbAnalyzeService;
import com.newegg.ec.redis.util.EurekaUtil;
import org.apache.commons.lang.StringUtils;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import redis.clients.jedis.Jedis;

import java.net.InetAddress;
import java.util.*;
import java.util.Map.Entry;


@Service
public class RdbAnalyzeService implements IRdbAnalyzeService {
    private static final Logger LOG = LoggerFactory.getLogger(RdbAnalyzeService.class);
    @Autowired
    RCTConfig config;

    @Autowired
    IRdbAnalyze iRdbAnalyze;

    @Autowired
    ClusterService clusterService;
    @Autowired
    RedisService redisService;
    @Autowired
    RdbAnalyzeService rdbAnalyzeService;

    @Autowired
    RestTemplate restTemplate;

    @Autowired
    RdbAnalyzeResultService rdbAnalyzeResultService;

    @Autowired
    ScheduleTaskService taskService;
    
	@Autowired
	IRdbAnalyzeResult rdbAnalyzeResultMapper;

    /**
     * 执行RDB分析任务
     *
     * @param id
     * @return { status：true/false, message:"...." }
     */
    @Override
    public JSONObject allocationRDBAnalyzeJob(Long id) {
        RDBAnalyze rdbAnalyze = this.selectById(id);
        return allocationRDBAnalyzeJob(rdbAnalyze);
    }


    @Override
    public JSONObject allocationRDBAnalyzeJob(RDBAnalyze rdbAnalyze) {
        JSONObject responseResult = new JSONObject();
        // 校验是否有任务在运行。
        boolean flag = ifRDBAnalyzeIsRunning(rdbAnalyze.getId());
        if (flag) {
            responseResult.put("status", Boolean.FALSE);
            responseResult.put("message", "There is a task in progress!");
            return responseResult;
        }

        // 启动状态监听线程，检查各个分析器分析状态和进程，分析完毕后，写数据库，发送邮件
        Thread analyzerStatusThread = new Thread(new AnalyzerStatusThread(this, restTemplate,
                rdbAnalyze, config.getEmail(), rdbAnalyzeResultService));
        analyzerStatusThread.start();

        responseResult.put("status", true);
        return responseResult;
    }

    // 执行RCT任务分发
    public JSONObject assignAnalyzeJob(RDBAnalyze rdbAnalyze){
        JSONObject responseResult = new JSONObject();
        int[] analyzer = null;
        if (rdbAnalyze.getAnalyzer().contains(",")) {
            String[] str = rdbAnalyze.getAnalyzer().split(",");
            analyzer = new int[str.length];
            for (int i = 0; i < str.length; i++) {
                analyzer[i] = Integer.parseInt(str[i]);
            }
        } else {
            analyzer = new int[1];
            analyzer[0] = Integer.parseInt(rdbAnalyze.getAnalyzer());
        }
        JSONArray tempArray = new JSONArray();
        RedisClient redisClient = null;
        Cluster cluster = rdbAnalyze.getCluster();

        String redisHost = cluster.getNodes().split(",")[0].split(":")[0];
        String redisPort = cluster.getNodes().split(",")[0].split(":")[1];


        // 集群中所有的host
        Map<String, String> clusterNodesIP = new HashMap<>();
        // 最终需要分析的机器及端口
        Map<String, Set<String>> generateRule = new HashMap<>();


        try {
            if (config.isDevEnable()) {
                clusterNodesIP.put(InetAddress.getLocalHost().getHostAddress(), config.getDevRDBPort());
                Set<String> set = new HashSet<>();
                set.add(config.getDevRDBPort());
                generateRule.put(InetAddress.getLocalHost().getHostAddress(), set);
                rdbAnalyze.setDataPath(config.getDevRDBPath());
            } else {
                // 获取集群中有哪些节点进行分析
                if (StringUtils.isNotBlank(cluster.getRedisPassword())) {
                    redisClient = RedisClientFactory.buildRedisClient(new RedisNode(redisHost,Integer.parseInt(redisPort)),cluster.getRedisPassword());
                } else {
                    redisClient = RedisClientFactory.buildRedisClient(new RedisNode(redisHost,Integer.parseInt(redisPort)),null);
                }
                // 集群中是否指定节点进行分析
                if(rdbAnalyze.getNodes() != null && !"-1".equalsIgnoreCase(rdbAnalyze.getNodes().get(0))){
                    //指定节点进行分析
                    List<String> nodeList = rdbAnalyze.getNodes();
                    Set<String> ports = null;
                    for (String node :nodeList){
                        String[] nodeStr = node.split(":");
                        if(generateRule.containsKey(nodeStr[0])){
                            ports = generateRule.get(nodeStr[0]);
                        }else {
                            ports = new HashSet<>();
                        }
                        clusterNodesIP.put(nodeStr[0],nodeStr[0]);
                        ports.add(nodeStr[1]);
                        generateRule.put(nodeStr[0],ports);
                    }
                }else{
                    clusterNodesIP = redisClient.nodesIP(cluster);
                    generateRule =  RedisClient.generateAnalyzeRule(redisClient.nodesMap(cluster));
                }

            }

        } catch (Exception e1) {
            responseResult.put("status", Boolean.FALSE);
            responseResult.put("message", e1.getMessage());
            return responseResult;
        } finally {
            if (null != redisClient) {
                redisClient.close();
            }
        }
        long scheduleID = System.currentTimeMillis();
        responseResult.put("scheduleID", scheduleID);
        // 初始化进度状态
        generateRule.forEach((host,ports) -> {
            ports.forEach(port -> {
                updateCurrentAnalyzerStatus(rdbAnalyze.getId(), scheduleID, host, port, 0,true, AnalyzeStatus.NOTINIT);
            });
        });

        List<AnalyzeInstance> analyzeInstances = EurekaUtil.getRegisterNodes();
        Map<String, AnalyzeInstance> analyzeInstancesMap = new HashMap<>(analyzeInstances.size());
        // 本次场景只会一个host一个AnalyzeInstance
        for (AnalyzeInstance instance : analyzeInstances) {
            analyzeInstancesMap.put(instance.getHost(), instance);
        }
        List<AnalyzeInstance> needAnalyzeInstances = new ArrayList<>();
        // 如果存在某个节点不存活，则拒绝执行本次任务
        for (String host : clusterNodesIP.keySet()) {
            AnalyzeInstance analyzeInstance = analyzeInstancesMap.get(host);
            if (analyzeInstance == null) {
                LOG.error("analyzeInstance inactive. ip:{}", host);
                responseResult.put("status", Boolean.FALSE);
                responseResult.put("message", host + " analyzeInstance inactive!");
                return responseResult;
            }
            needAnalyzeInstances.add(analyzeInstance);
        }
        // 保存分析job到数据库
        saveToResult(rdbAnalyze,scheduleID);
        responseResult.put("needAnalyzeInstances",needAnalyzeInstances);

        for (String host : clusterNodesIP.keySet()) {
            // 处理无RDB备份策略情况
            if ((!config.isDevEnable()) && config.isRdbGenerateEnable()
                    && generateRule.containsKey(host)) {

                Set<String> ports = generateRule.get(host);
                ports.forEach(port -> {
                    Jedis jedis = null;
                    try {
                        jedis = new Jedis(host, Integer.parseInt(port));
                        if (StringUtils.isNotBlank(cluster.getRedisPassword())) {
                            jedis.auth(cluster.getRedisPassword());
                        }
                        List<String> configs = jedis.configGet("save");
                        // 如果没有配置RDB持久化策略，那么使用命令生成RDB文件
                        if (configs == null || configs.size() == 0 ||
                                (configs.size() == 2 && StringUtils.isBlank(configs.get(1)))) {
                            // 调用save命令前设置执行状态为save
                            updateCurrentAnalyzerStatus(rdbAnalyze.getId(), scheduleID, host, port, 0,true, AnalyzeStatus.SAVE);
                            rdbBgsave(jedis,host,port);
                        }
                    } catch (Exception e) {
                        LOG.error("rdb generate error.", e);
                    } finally {
                        if (jedis != null) {
                            jedis.close();
                        }
                    }
                });
            }

            // 如果某个服务器上没有分配到分析节点，则直接跳过
            if(!generateRule.containsKey(host)){
                continue;
            }

            // 调用分析器执行任务， 如果执行失败则停止进行分析任务
            if(!requestAnalyze(scheduleID, rdbAnalyze, analyzeInstancesMap.get(host), generateRule, analyzer)){
                responseResult.put("status", Boolean.FALSE);
                return responseResult;
            }
        }
        AppCache.scheduleProcess.put(rdbAnalyze.getId(), scheduleID);

        if (!config.isDevEnable()) {
            // 设置db数据量
            cacheDBSize(generateRule);
        }
        return responseResult;
    }

    private boolean requestAnalyze(long scheduleID, RDBAnalyze rdbAnalyze, AnalyzeInstance analyzeInstance,
                                Map<String, Set<String>> generateRule, int[] analyzer){
        // 执行任务分发
        String host = analyzeInstance.getHost();
        String url = "http://" + analyzeInstance.getHost() + ":" + analyzeInstance.getPort() + "/receivedSchedule";
        //String url = "http://127.0.0.1:8082/receivedSchedule";
        ScheduleInfo scheduleInfo = new ScheduleInfo(scheduleID, rdbAnalyze.getDataPath(), rdbAnalyze.getPrefixes(),
                generateRule.get(host), analyzer);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        HttpEntity<ScheduleInfo> httpEntity = new HttpEntity<ScheduleInfo>(scheduleInfo, headers);
        boolean status = Boolean.FALSE;
        try {
            ResponseEntity<String> executeResult = restTemplate.postForEntity(url, httpEntity, String.class);
            JSONObject responseMessage = JSONObject.parseObject(executeResult.getBody());
            if (!responseMessage.getBooleanValue("checked")) {
                LOG.error("allocation {} scheduleJob response error. responseMessage:{}",
                        analyzeInstance.toString(), responseMessage.toJSONString());
                deleteResult(rdbAnalyze,scheduleID);
            } else {
                status = Boolean.TRUE;
            }

        } catch (Exception e) {
            LOG.error("allocation {} scheduleJob fail. ", analyzeInstance.toString(), e);
            deleteResult(rdbAnalyze,scheduleID);
        }
        // 更改页面进度显示状态
        Set<String> ports = generateRule.get(host);
        for (String port : ports) {
            if(status){
                updateCurrentAnalyzerStatus(rdbAnalyze.getId(), scheduleID, host, port, 0, status, AnalyzeStatus.CHECKING);
            } else {
                updateCurrentAnalyzerStatus(rdbAnalyze.getId(), scheduleID, host, port, 0, status, AnalyzeStatus.ERROR);
            }
        }
        return status;
    }

    private void updateCurrentAnalyzerStatus(Long analyzeID, long scheduleID, String host, String port, int process,
                                             boolean stats, AnalyzeStatus analyzeStatus) {
        String instance = host + ":" + port;
        ScheduleDetail scheduleDetail = new ScheduleDetail(scheduleID, instance, process, stats, analyzeStatus);
        List<ScheduleDetail> scheduleDetails = AppCache.scheduleDetailMap.computeIfAbsent(analyzeID, k -> new ArrayList<>());
        if(scheduleDetails.contains(scheduleDetail)){
            int i = scheduleDetails.indexOf(scheduleDetail);
            scheduleDetails.set(i, scheduleDetail);
        }else {
            scheduleDetails.add(scheduleDetail);
        }
    }


    // 将每个需要分析的redis实例的key数量写入到缓存中
    private void cacheDBSize(Map<String, Set<String>> generateRule){
        for (Entry<String, Set<String>> map : generateRule.entrySet()) {
            String ip = map.getKey();
            for (String ports : map.getValue()) {
                Jedis jedis = null;
                try {
                    jedis = new Jedis(ip, Integer.parseInt(ports));
                    AppCache.keyCountMap.put(ip + ":" + ports, Float.parseFloat(String.valueOf(jedis.dbSize())));
                } catch (Exception e) {
                    LOG.error("redis get db size has error!", e);
                } finally {
                    if (jedis != null) {
                        jedis.close();
                    }
                }
            }
        }
    }




    private void saveToResult(RDBAnalyze rdbAnalyze,Long scheduleId){
        RDBAnalyzeResult rdbAnalyzeResult = new RDBAnalyzeResult();
        rdbAnalyzeResult.setAnalyzeConfig(JSONObject.toJSONString(rdbAnalyze));
        rdbAnalyzeResult.setClusterId(Long.parseLong(rdbAnalyze.getCluster().getClusterId().toString()));
        rdbAnalyzeResult.setScheduleId(scheduleId);
        rdbAnalyzeResult.setGroupId(rdbAnalyze.getGroupId());
        rdbAnalyzeResult.setDone(false);
        rdbAnalyzeResultService.add(rdbAnalyzeResult);
    }

    public void deleteResult(RDBAnalyze rdbAnalyze,Long scheduleId){
        Map<String,Long> map = new HashMap<>();
        map.put("cluster_id",rdbAnalyze.getClusterId());
        map.put("schedule_id",scheduleId);
        rdbAnalyzeResultMapper.deleteRdbAnalyzeResult(map);
    }

    @Override
    public JSONObject cancelRDBAnalyze(String instance, String scheduleID)  {
        JSONObject result = new JSONObject();
        if (null == instance || "".equals(instance)) {
            LOG.warn("instance is null!");
            result.put("canceled", false);
            return result;
        }
    	Map<String, Long> rdbAnalyzeResult = new HashMap<String, Long>();
        rdbAnalyzeResult.put("cluster_id", Long.valueOf(instance));
        rdbAnalyzeResult.put("schedule_id", Long.valueOf(scheduleID));
        rdbAnalyzeResultMapper.deleteRdbAnalyzeResult(rdbAnalyzeResult);
        Cluster cluster = clusterService.getClusterById(Integer.valueOf(instance));
        String[] hostAndPort = cluster.getNodes().split(",")[0].split(":");
        List<AnalyzeInstance> analyzeInstances = EurekaUtil.getRegisterNodes();
        AnalyzeInstance analyzeInstance = null;
        for (AnalyzeInstance analyze : analyzeInstances) {
            if (hostAndPort[0].equals(analyze.getHost())) {
                analyzeInstance = analyze;
                break;
            }
        }
        if (null == analyzeInstance) {
            LOG.warn("analyzeInstance is null!");
            result.put("canceled", false);
            return result;
        }
        //	 String url = "http://127.0.0.1:8082/cancel";
        String url = "http://" + analyzeInstance.getHost() + ":" + analyzeInstance.getPort() + "/cancel";
        try {
            ResponseEntity<String> responseEntity = restTemplate.getForEntity(url, String.class);
            result = JSONObject.parseObject(responseEntity.getBody());
            if (null == result) {
                LOG.warn("URL :" + url + " no response");
                result = new JSONObject();
                result.put("canceled", false);
                return result;
            }
        } catch (Exception e) {
            LOG.error("canceledInstance is failed!", e);
            result = new JSONObject();
            result.put("canceled", false);
            return result;
        }
        return result;
    }

    @Override
    public RDBAnalyze selectById(Long id) {
        return iRdbAnalyze.selectById(id);
    }

    @Override
    public boolean update(RDBAnalyze rdbAnalyze) {
        int result = iRdbAnalyze.updateRdbAnalyze(rdbAnalyze);
        return checkResult(result);
    }



    @Override
    public boolean add(RDBAnalyze rdbAnalyze) {
        int result = iRdbAnalyze.insert(rdbAnalyze);
        return checkResult(result);
    }

    @Override
    public List<RDBAnalyze> list(Long groupId) {
        List<RDBAnalyze> results = iRdbAnalyze.queryList(groupId);
        return results;
    }

    @Override
    public List<RDBAnalyze> selectALL() {
        return iRdbAnalyze.list();
    }

    @Override
    public boolean checkResult(Integer result) {
        if (result > 0) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public RDBAnalyze getRDBAnalyzeByPid(Long cluster_id) {
        RDBAnalyze rdbAnalyze = iRdbAnalyze.getRDBAnalyzeByCluster_id(cluster_id);
        return rdbAnalyze;
    }

    @Override
    public RDBAnalyze getRDBAnalyzeById(Long id) {
        return iRdbAnalyze.getRDBAnalyzeById(id);
    }

    /**
     *
     * @param
     * @return boolean true: has task running false: no task running
     */
    @Override
    public boolean ifRDBAnalyzeIsRunning(Long id) {
        List<ScheduleDetail> scheduleDetail = AppCache.scheduleDetailMap.get(id);
        // default no task running
        boolean result = false;
        if (scheduleDetail != null && scheduleDetail.size() > 0) {
            for (ScheduleDetail scheduleDetails : scheduleDetail) {
                AnalyzeStatus status = scheduleDetails.getStatus();
                if ((!status.equals(AnalyzeStatus.DONE)) && (!status.equals(AnalyzeStatus.CANCELED))
                        && (!status.equals(AnalyzeStatus.ERROR))) {
                    result = true;
                    break;
                }
            }
        }
        return result;
    }

    /**
     * get Redis Id Base
     *
     * @return rdb_analyze.id
     */
    @Override
    public Long getRedisIDBasePID(Long cluster_id) {
        if (null == cluster_id) {
            return null;
        }
        return iRdbAnalyze.getRDBAnalyzeIdByCluster_id(cluster_id);
    }

    @Override
    public boolean updateRdbAnalyze(RDBAnalyze rdbAnalyze) {
        return checkResult(iRdbAnalyze.updateRdbAnalyze(rdbAnalyze));
    }

    public void updateJob() {
        updateJob();
    }

    @Override
    public void updateJob(RDBAnalyze rdbAnalyze) {
        try {
            taskService.addTask(rdbAnalyze, RDBScheduleJob.class);
        } catch (SchedulerException e) {
            LOG.warn("schedule job update faild!message:{}", e.getMessage());
        }
    }

    @Override
    public void rdbBgsave(Jedis jedis, String host, String port) {
        Long currentTime = System.currentTimeMillis();
        Long oldLastsave = jedis.lastsave();
        LOG.info("RCT start to generate redis rdb file.{}:{}", host, port);
        jedis.bgsave();
        boolean isCheck = true;
        while(isCheck) {
            try {
                //等待5s，查看rdb文件是否生成完毕！
                Thread.sleep(5000);
            } catch (Exception e) {
                e.printStackTrace();
            }
            Long lastsave = jedis.lastsave();
            if(!lastsave.equals(oldLastsave)) {
                isCheck = false;
            }
        }
        LOG.info("RCT generate redis rdb file success. cost time:{} ms", (System.currentTimeMillis()-currentTime));
    }

    /**
     * 根据id查询pid
     *
     * @param id
     * @return
     */
    @Override
    public Long selectClusterIdById(Long id) {
        if (null == id) {
            return null;
        }
        return iRdbAnalyze.selectClusterIdById(id);
    }

    @Override
    public void createRdbAnalyzeTable() {
        iRdbAnalyze.createRdbAnalyzeTable();
    }

    @Override
    public boolean deleteRdbAnalyze(Long id) {
        return 	checkResult(iRdbAnalyze.delete(id));
    }

    @Override
    public boolean exitsRdbAnalyze(RDBAnalyze rdbAnalyze) {
        return checkResult(iRdbAnalyze.exits(rdbAnalyze));
    }
}
