package net.floodlightcontroller.loadbalancer.RouteByToS;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.SingletonTask;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkCostService.ILinkCostService;
import net.floodlightcontroller.linkCostService.LinkBandwidthType;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.LinkInfo;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.routing.RouteId;
import net.floodlightcontroller.threadpool.IThreadPoolService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.OFMessageDamper;
import org.openflow.protocol.*;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Created by Victor on 2017/2/8.
 */
public class RouteByToS implements IFloodlightModule, IRouteByToS, IOFMessageListener{
    private IFloodlightProviderService floodlightProvider;
    private IThreadPoolService threadPool;
    private ILinkDiscoveryService linkDiscoveryManager;
    private ILinkCostService linkCostService;
    private IDeviceService deviceManager;
    protected ICounterStoreService counterStore;
    protected OFMessageDamper messageDamper;
    private SingletonTask newInstanceTask;

    // more flow-mod defaults
    protected static int OFMESSAGE_DAMPER_CAPACITY = 10000; // TODO: find sweet spot
    protected static int OFMESSAGE_DAMPER_TIMEOUT = 250; // ms
    protected static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5; // in seconds
    protected static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
    protected static short FLOWMOD_PRIORITY = 100;

    //当前网络中链路的最大容量
    protected static double maxLinkCompacity;

    //业务的带宽占用类型<ToS号，带宽占用>
    private static Map<Integer, Double> BandwidthType = new HashMap<>();

    //业务的丢包率要求<ToS号，丢包率要求百分比>
    private static Map<Integer, Double> LossRateType = new HashMap<>();

    //业务的时延要求
    private static Map<Integer, Integer> DelayType = new HashMap<>();

    //ToS分级数目
    //private static int ToSLevelNum ;
    //拓扑描述
    Map<Long, Set<Link>> switchLinks = new HashMap<>();
    Map<Link, LinkInfo> allLinks = new HashMap<>();
    //链路权重<链路，速率>
    private Map<Link, Double> linkCost = new HashMap<>();
    //链路带宽容量map
    private Map<Link,LinkBandwidthType> linkTypeMap = new HashMap<>();
    //预测链路权重<链路，速率>
    private Map<Link, Double> predictLinkCost;
    //拓扑图<dpId, 链路>
    private Map<Long, Set<Link>> wholeTopology;


    //dpId和拓扑邻接矩阵下标之间的映射关系
    private Map<Long, Integer>  dpIdMap;
    private Map<Integer, Long>  IndexMap;

    //拓扑图(邻接矩阵形式)
    private List<List<Link>> TopoMatrix;
    //拓扑中的设备
    private Collection<? extends IDevice> allDevices;
    //拓扑中的交换机数目
    private int switchNum = 0;
    // 路由表，即ToS分级下的path
    //private Map<Byte, List<List<Integer>>> routeTable;
    // 距离表，即ToS分级下的dist
    //private Map<Byte, List<List<Integer>>> dist;
    // 路由结果
    private TreeMap<Byte,Map<RouteId,Route>> routeCache = new TreeMap<>();



    //IP地址对应的接入点
    private Map<Integer, SwitchPort>  attachmentMap = new HashMap<>();
    // 顶点集合
    private char[] mVexs;
    // ToS分级下的拓扑
    //private List<List<List<Integer>>> mMatrix;
    // 最大值
    private static final int INF = Integer.MAX_VALUE;

    protected static Logger log = LoggerFactory.getLogger(RouteByToS.class);

    /**
     * 完成对网络拓扑信息的复制, 将网络的初始拓扑保存下来
     * getSwitchLinks获得的是双工链路，这里将其视为一条
     */
    public void copySwitchLinks() {
        synchronized (linkCostService) {
            linkCost.clear();
            switchLinks.clear();
            allLinks.clear();
            linkTypeMap.clear();
            switchLinks.putAll(linkCostService.getSwitchLinks());
            allLinks.putAll(linkCostService.getLinks());
            linkCost.putAll(linkCostService.getLinkCost());  //获取链路速率
            maxLinkCompacity = linkCostService.getMaxLinkCompacity();
            linkTypeMap.putAll(linkCostService.getLinkTypeMap());
        }
        //如果linkDiscoveryManager还未更新则不更新任何数据
        if(switchLinks==null||switchLinks.isEmpty())    return;

        for(Link link : linkCost.keySet()){
            log.info("LinkCostRecord {} to {} : {} Mbps",new Object[]{link.getSrc(),link.getDst(),linkCost.get(link)});
        }

        /**
         * 将拓扑结构复制为邻接矩阵的形式
         * @Author Victor
         */
        wholeTopology = new HashMap<>();
        TopoMatrix = new ArrayList<>();
        dpIdMap = new HashMap<>();
        IndexMap = new HashMap<>();
        Set<Long> allSwitches = switchLinks.keySet();
        switchNum = allSwitches.size();

        int index = 0;
        //建立dpid和index之间的映射关系
        for(long dpid : allSwitches){
            dpIdMap.put(dpid, index);
            IndexMap.put(index, dpid);
            index++;
        }

        for(int i=0;i<switchNum;i++){   //初始化TopoMatrix
            TopoMatrix.add(new ArrayList<>(switchNum));
            for(int j=0;j<switchNum;j++)   {
                if(i!=j)    TopoMatrix.get(i).add(null);
                else        TopoMatrix.get(i).add(new Link());  // 什么都没有的link表示自己连接自己
            }
        }
        //建立邻接矩阵形式的拓扑图
        for(Link link : allLinks.keySet()){
            int srcIndex = dpIdMap.get(link.getSrc());
            int dstIndex = dpIdMap.get(link.getDst());
            TopoMatrix.get(srcIndex).set(dstIndex, link);
            //TopoMatrix.get(dstIndex).set(srcIndex, link);
        }
        log.info("copy topo finished");
		/*
		 * Set<Long> dpids=wholeTopology.keySet(); Iterator<Long>
		 * iter3=keys.iterator(); while(iter3.hasNext()){ long
		 * dpid=iter3.next(); Set<Link> links=wholeTopology.get(dpid);
		 * Iterator<Link> iter4=links.iterator(); while(iter4.hasNext()){ Link
		 * link=iter4.next(); System.out.println(link); } }
		 */
    }

    /**
     * floyd最短路径。
     * 即，统计图中各个顶点间的最短路径。
     *
     * 参数说明：
     *     path -- 路径。path[i][j]=k表示，"顶点i"到"顶点j"的最短路径会经过顶点k，即routeTable[i]
     *     dist -- 长度数组。即，dist[i][j]=sum表示，"顶点i"到"顶点j"的最短路径的长度是sum。
     *     mMatrix -- 某个ToS阈值下的拓扑邻接矩阵
     */
    public void floyd(int[][] path, int[][] dist, int[][] mMatrix) {

        // 初始化
        for (int i = 0; i < switchNum; i++) {
            for (int j = 0; j < switchNum; j++) {
                dist[i][j] = mMatrix[i][j];    // "顶点i"到"顶点j"的路径长度为"i到j的权值"。
                path[i][j] = j;                // "顶点i"到"顶点j"的最短路径是经过顶点j。
            }
        }

        // 计算最短路径
        for (int k = 0; k < switchNum; k++) {
            for (int i = 0; i < switchNum; i++) {
                for (int j = 0; j < switchNum; j++) {

                    // 如果经过下标为k顶点路径比原两点间路径更短，则更新dist[i][j]和path[i][j]
                    int tmp = (dist[i][k]==INF || dist[k][j]==INF) ? INF : (dist[i][k] + dist[k][j]);
                    if (dist[i][j] > tmp) {
                        // "i到j最短路径"对应的值设，为更小的一个(即经过k)
                        dist[i][j] = tmp;
                        // "i到j最短路径"对应的路径，经过k
                        path[i][j] = path[i][k];
                    }
                }
            }
        }
    }


    public void floyd(List<List<Integer>> path, List<List<Integer>> dist, List<List<Integer>> mMatrix) {
        try {
            // 初始化
            for (int i = 0; i < switchNum; i++) {
                for (int j = 0; j < switchNum; j++) {
                    dist.get(i).set(j, mMatrix.get(i).get(j));    // "顶点i"到"顶点j"的路径长度为"i到j的权值"。
                    path.get(i).set(j, j);                // "顶点i"到"顶点j"的最短路径是经过顶点j。
                }
            }

            // 计算最短路径
            for (int k = 0; k < switchNum; k++) {
                for (int i = 0; i < switchNum; i++) {
                    for (int j = 0; j < switchNum; j++) {
                        // 如果经过下标为k顶点路径比原两点间路径更短，则更新dist[i][j]和path[i][j]
                        int tmp = (dist.get(i).get(k) == INF || dist.get(k).get(j) == INF) ? INF : (dist.get(i).get(k) + dist.get(k).get(j));
                        if (dist.get(i).get(j) > tmp) {
                            // "i到j最短路径"对应的值设，为更小的一个(即经过k)
                            dist.get(i).set(j, tmp);
                            // "i到j最短路径"对应的路径，经过k (path是从第一个坐标为起点，到达第二个坐标时的下一跳)
                            path.get(i).set(j, path.get(i).get(k));
                        }
                    }
                }
            }
        }catch (Exception e){
            log.error("Floyd failed to execute!");
        }
    }

    /**
     * 根据ToS级别生成对应的拓扑图，然后得到对应的路由表
     * @return
     */
    public void routeCompute(){
        if(predictLinkCost==null||predictLinkCost.isEmpty()){
            log.error("LinkCost is null");
            return;
        }
        //初始化距离矩阵
        //dist = new HashMap<>();
        //初始化总的routeTable
        //routeTable = new HashMap<>();
        for(Byte ToS : routeCache.keySet()){
            double threshold = ThresholdCompute(ToS);
            //初始化每一张routeTable、dist
            //routeTable.put(ToS, new ArrayList<>());
            //dist.put(ToS, new ArrayList<>());
            //不同ToS分级下的邻接矩阵(1表示邻接)
            List<List<Integer>> curTopoMatrix = new ArrayList<>();
            //初始化邻接矩阵
            for(int i=0;i<switchNum;i++){
                curTopoMatrix.add(new ArrayList<>(switchNum));
                for(int j=0;j < switchNum;j++){
                    if(i!=j)    curTopoMatrix.get(i).add(INF);
                    else    curTopoMatrix.get(i).add(0);
                }
            }
            List<List<Integer>> curRouteTable = new ArrayList<>();
            List<List<Integer>> curDist = new ArrayList<>();

            //List<List<Integer>> everyRouteTable = routeTable.get(ToS);
            //List<List<Integer>> everyDist = dist.get(ToS);

            for(int j=0;j<switchNum;j++){
                curRouteTable.add(new ArrayList<>(switchNum));
                curDist.add(new ArrayList<>(switchNum));
                //在floyd算法中还会被初始化
                for(int k=0;k<switchNum;k++){
                    curRouteTable.get(j).add(INF);
                    curDist.get(j).add(INF);
                }
            }
            Set<Link> linkSet = predictLinkCost.keySet();
            //构造当前ToS下的拓扑邻接矩阵
            for(Link link : linkSet){
                double curLoad = predictLinkCost.get(link);
                double curLeftBandwidth =  linkTypeMap.get(link).getBandwidth()-curLoad;
                int srcIndex = dpIdMap.get(link.getSrc());
                int dstIndex = dpIdMap.get(link.getDst());
                if(curLeftBandwidth >= threshold) {   //当剩余带宽大于等于门限，则链路开放
                    if(allLinks.containsKey(link)) curTopoMatrix.get(srcIndex).set(dstIndex, 1);
                    //curTopoMatrix.get(dstIndex).set(srcIndex, 1);
                }
            }
            //Floyd算法计算该ToS下的最短路
            floyd(curRouteTable,curDist,curTopoMatrix);
            //将路由计算结果写入routeCache
            Map<RouteId,Route> curCache = routeCache.get(ToS);
            //循环+递归更新路径cache
            synchronized (curCache) {
                curCache.clear();   //更新前应先删除当前级别的cache
                for (int src = 0; src < switchNum; src++) {
                    for (int dst = 0; dst < switchNum; dst++) {
                        addCache(src, dst, curCache, curDist, curRouteTable);
                    }
                }
            }
        }

    }

    /**
     * 递归增添路径到cache
     * @param src 起点对应邻接矩阵的下标
     * @param dst 终点对应的邻接矩阵的下标
     * @param curCache 当前ToS级别下的路径cache
     * @param everyDist 当前ToS级别下的距离矩阵
     * @param everyRouteTable 当前ToS级别下的路由表
     */
    public void addCache(int src,int dst,
                         Map<RouteId,Route> curCache,
                         List<List<Integer>> everyDist,
                         List<List<Integer>> everyRouteTable
                         )
    {
        //如果路径存在并且在cache中不存在该路径则添加路径(源和目的相同的情况不包括在内)
        if(everyDist.get(src).get(dst)!=INF && src!=dst) {
            RouteId curId = new RouteId(IndexMap.get(src),IndexMap.get(dst));
            if(!curCache.containsKey(curId)){
                List<NodePortTuple> path = new ArrayList<>();
                int nextHopIndex = everyRouteTable.get(src).get(dst);
                int routeCount = 0;
                if(nextHopIndex!=dst){
                    RouteId nextHopId = new RouteId((IndexMap.get(nextHopIndex)),IndexMap.get(dst));
                    //如果cache中不存在下一跳到终点的记录，则递归寻找
                    if(!curCache.containsKey(nextHopId)){
                        addCache(nextHopIndex,dst,curCache,everyDist,everyRouteTable);
                    }
                    Route tmp = curCache.get(nextHopId);
                    path.addAll(tmp.getPath());
                    routeCount = tmp.getRouteCount();
                }
                try {
                    //根据拓扑矩阵确定src到nextHop之间的端口对应关系
                    Link link = TopoMatrix.get(src).get(nextHopIndex);
                    NodePortTuple srcNPT = new NodePortTuple(link.getSrc(), link.getSrcPort());
                    NodePortTuple nextHopNPT = new NodePortTuple(link.getDst(), link.getDstPort());
                    path.add(0,nextHopNPT);
                    path.add(0,srcNPT);
                    Route tmp = new Route(curId,path);
                    tmp.setRouteCount(routeCount++);
                    curCache.put(curId,tmp);
                }catch (NullPointerException e){
                    log.error("TopoMatrix is not consist with RouteTable");
                }
            }
        }
    }

    /**
     * 根据ToS计算threshold的工具类
     */
    private double ThresholdCompute(byte ToS){
        try {
            double RequiredBandwith = BandwidthType.get((ToS>>4)&(byte)3);
            double RequiredLossRate = LossRateType.get((ToS>>3)&(byte)1);
            int RequiredDelay = DelayType.get(ToS&(byte)7);
            double basicThreshold = (1-RequiredLossRate)*RequiredBandwith;
            double diff = maxLinkCompacity-basicThreshold;
            int bucketNum = Math.min((int)(diff/BandwidthType.get(1)),DelayType.size());
            //匹配桶的区域
            for (double i=bucketNum-1.0;i>=0.0;i--){
                if((double)RequiredDelay/DelayType.size()>=i/bucketNum)  return  basicThreshold+i/bucketNum*diff;
            }
//            for (double i=1.0;i<=bucketNum;i++){
//                if((double)RequiredDelay/DelayType.size()>i/bucketNum)   return  basicThreshold+i/bucketNum*diff;
//            }
            return basicThreshold;
        }catch (Exception e){
            log.warn("No suitable ToS found");
            return 0.0;
        }

    }
    /**
     * 生成默认OFMatch的工具类
     * 参考pushStaticVipRoute
     */
    private OFMatch matchGenerate(Integer dstIP, byte ToS, IOFSwitch sw){
        String matchString = null;
        matchString = "nw_dst="+ IPv4.fromIPv4Address(dstIP)+","
                    + "nw_tos="+ToS;
        OFMatch ofMatch = new OFMatch();
        try {
            ofMatch.fromString(matchString);
        }catch (Exception e){
            log.error("Fail to generate OFMatch");
        }

        // L2 only wildcard if there is no prior route decision
        Integer wildcard_hints = null;
        wildcard_hints = ((Integer) sw
                .getAttribute(IOFSwitch.PROP_FASTWILDCARDS))
                .intValue()
                & ~OFMatch.OFPFW_IN_PORT
                & ~OFMatch.OFPFW_DL_VLAN
                & ~OFMatch.OFPFW_DL_SRC
                & ~OFMatch.OFPFW_DL_DST
                & ~OFMatch.OFPFW_NW_SRC_MASK
                & ~OFMatch.OFPFW_NW_DST_MASK;
        return ofMatch.setWildcards(wildcard_hints.intValue());

    }

    /**
     * 下发匹配各个ToS字段的FlowMod(借鉴LearningSwitch中的writeFlowMod)
     *
     * @return
     */
    private void writeFlowMod(IOFSwitch sw, short command, int bufferId,
                              OFMatch match, short outPort) {

        OFFlowMod flowMod = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
        flowMod.setMatch(match);
        flowMod.setCookie(0);
        flowMod.setCommand(command);
        flowMod.setIdleTimeout(RouteByToS.FLOWMOD_DEFAULT_IDLE_TIMEOUT);
        flowMod.setHardTimeout(RouteByToS.FLOWMOD_DEFAULT_HARD_TIMEOUT);
        flowMod.setPriority(RouteByToS.FLOWMOD_PRIORITY);
        flowMod.setBufferId(bufferId);
        flowMod.setOutPort((command == OFFlowMod.OFPFC_DELETE) ? outPort : OFPort.OFPP_NONE.getValue());
        flowMod.setFlags((command == OFFlowMod.OFPFC_DELETE) ? 0 : (short) (1 << 0)); // OFPFF_SEND_FLOW_REM
        flowMod.setActions(Arrays.asList((OFAction) new OFActionOutput(outPort, (short) 0xffff)));
        flowMod.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));

        if (log.isTraceEnabled()) {
            log.trace("{} {} flow mod {}",
                    new Object[]{ sw, (command == OFFlowMod.OFPFC_DELETE) ? "deleting" : "adding", flowMod });
        }

        counterStore.updatePktOutFMCounterStoreLocal(sw, flowMod);

        // and write it out
        try {
            sw.write(flowMod, null);
        } catch (IOException e) {
            log.error("Failed to write {} to switch {}", new Object[]{ flowMod, sw }, e);
        }
    }




    /**
     * 遍历查找所有接入主机的路由，并下发对应流表到各交换机(借鉴Forwarding中的pushRoute)
     * 借鉴pushRoute()的写法
     */
    public void UpdateFlowTable(){
        for(Byte ToS : routeCache.keySet()){
            Byte curToS = ToS;                              //用于在没有合适路由的情况下改变路由级别
            Set<Integer> IPSet = attachmentMap.keySet();    //遍历所有登记过的主机地址
            for(Integer IpSrc : IPSet){
                for(Integer IpDst : IPSet) {
                    if (IpDst.equals(IpSrc)) continue;
                    SwitchPort dst = attachmentMap.get(IpDst);
                    SwitchPort src = attachmentMap.get(IpSrc);

                    while (curToS-- > 0){
                        Route route = getRoute(src.getSwitchDPID(), (short) src.getPort(),
                                dst.getSwitchDPID(), (short) dst.getPort(),
                                0, curToS, true);

                        //如果当前ToS下有合适的链路
                        if (route != null) {
                            List<NodePortTuple> path = route.getPath();

                            for (int indx = path.size() - 1; indx > 0; indx -= 2) {
                                long switchDPID = path.get(indx).getNodeId();
                                IOFSwitch sw = floodlightProvider.getSwitch(switchDPID);
                                if (sw == null) {
                                    if (log.isWarnEnabled()) {
                                        log.warn("Unable to push route, switch at DPID {} " +
                                                "not available", switchDPID);
                                    }
                                    break;
                                }
                                OFMatch match = matchGenerate(IpDst, ToS, sw);
                                short outPort = path.get(indx).getPortId();
                                writeFlowMod(sw, OFFlowMod.OFPFC_ADD, OFPacketOut.BUFFER_ID_NONE, match, outPort);

                            }
                            break;
                        }
                        //if(curToS>0)    log.info("No matched route for current ToS , auto decrease ToS level to {}",curToS);
                        //else            log.warn("No matched route in route cache");
                    }
                }
            }
        }
    }

    public Map<Link, Double> getLinkCost() {
        return linkCost;
    }

    public Map<Long, Set<Link>> getWholeTopology() {
        return wholeTopology;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IRouteByToS.class);
        return l;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
        // We are the class that implements the service
        m.put(IRouteByToS.class, this);
        return m;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l =
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
        return l;
    }

    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        messageDamper = new OFMessageDamper(OFMESSAGE_DAMPER_CAPACITY,
                EnumSet.of(OFType.FLOW_MOD),
                OFMESSAGE_DAMPER_TIMEOUT);
        floodlightProvider = context
                .getServiceImpl(IFloodlightProviderService.class);
        threadPool = context.getServiceImpl(IThreadPoolService.class);
        linkCostService = context.getServiceImpl(ILinkCostService.class);
        linkDiscoveryManager = context
                .getServiceImpl(ILinkDiscoveryService.class);
        deviceManager = context.getServiceImpl(IDeviceService.class);
        counterStore = context.getServiceImpl(ICounterStoreService.class);

        //初始化各个ToS类型
        //前两位
        BandwidthType.put(0, 0.0);    //0表示无带宽占用
        BandwidthType.put(1, 1.0);    //1表示低带宽占用
        BandwidthType.put(2, 2.0);    //2表示高带宽占用
        BandwidthType.put(3, 3.0);    //3表示极高带宽占用
        //中间一位
        LossRateType.put(0,1.0);       //0表示无要求
        LossRateType.put(1,0.0);       //1表示有要求
        //后三位
        DelayType.put(0, 0);           //0表示高时延要求
        DelayType.put(1, 1);
        DelayType.put(2, 2);
        DelayType.put(3, 3);
        DelayType.put(4, 4);
        DelayType.put(5, 5);
        DelayType.put(6, 6);
        DelayType.put(7, 7);           //7表示低时延要求

        //我不确定这个是不是需要，先写着吧
        //ToSLevelNum = BandwidthType.size()*LossRateType.size()*DelayType.size();

        //初始化自定义ToS类型
        for(Integer bandwith : BandwidthType.keySet()){
            for(Integer lossRate : LossRateType.keySet()){
                for(Integer delay : DelayType.keySet()){
                    byte ToS = (byte)((bandwith<<4)|(lossRate<<3)|delay);
                    routeCache.put(ToS,new HashMap<>());
                }
            }
        }
    }

    @Override
    public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
        wholeTopology = new HashMap<Long, Set<Link>>();
        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);


//        ScheduledExecutorService ses = threadPool.getScheduledExecutor();
//        newInstanceTask = new SingletonTask(ses, new Runnable(){
//           public void run(){
//               try {
//                   //linkCostService.runLinkCostService();
//                   copySwitchLinks();  //获取拓扑
//                   linkCost = linkCostService.getLinkCost();   //获取链路速率
//                   predictLinkCost = linkCost;     //暂时先这么写
//                   routeCompute();
//                   UpdateFlowTable();
//                   //allDevices = deviceManager.getAllDevices();
//                   log.info("run RouteByToS");
//               }catch (Exception e){
//                   log.error("exception",e);
//               }finally{
//                   newInstanceTask.reschedule(30, TimeUnit.SECONDS);
//               }
//           }
//        });
//        newInstanceTask.reschedule(5, TimeUnit.SECONDS);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                   while(true) {
                       try {
                           copySwitchLinks();  //获取拓扑
                           predictLinkCost = linkCost;     //暂时先这么写
                           routeCompute();
                           for(Byte tos : routeCache.keySet()){
                               for(RouteId rID : routeCache.get(tos).keySet()){
                                   if(rID.getSrc().equals(new Long(1))&&rID.getDst().equals(new Long(8))){
                                       log.info("ToS {} route 1 to 8 : next hop is {}", new Object[]{tos,
                                               routeCache.get(tos).get(rID).getPath().get(1).getNodeId()});
                                   }
                               }
                           }
                           UpdateFlowTable();
                           //allDevices = deviceManager.getAllDevices();
                           log.info("run RouteByToS");
                       } catch (Exception e) {
                           e.printStackTrace();
                       } finally {
                           try {
                               Thread.sleep(5000);
                           } catch (InterruptedException e) {
                               e.printStackTrace();
                           }
                       }
                   }

            }
        });
        t.start();
    }
    @Override
    public Route getRoute(long srcId, short srcPort, long dstId, short dstPort, long cookie, Byte TosLevel, boolean tunnelEnabled){
        if (srcId == dstId && srcPort == dstPort)
            return new Route(srcId,dstId);

        List<NodePortTuple> nptList;
        NodePortTuple npt;
        Route r = getRoute(srcId, dstId,0 , TosLevel, true);
        if (r == null && srcId != dstId) return null;

        if (r != null) {
            nptList= new ArrayList<NodePortTuple>(r.getPath());
        } else {
            nptList = new ArrayList<NodePortTuple>();
        }
        npt = new NodePortTuple(srcId, srcPort);
        nptList.add(0, npt); // add src port to the front
        npt = new NodePortTuple(dstId, dstPort);
        nptList.add(npt); // add dst port to the end

        RouteId id = new RouteId(srcId, dstId);
        r = new Route(id, nptList);
        return r;
    }
    @Override
    public Route getRoute(long src, long dst, long cookie, Byte ToS, boolean tunnelEnabled){
        if(src==dst) return new Route(src,dst);
        RouteId id = new RouteId(src, dst);
        Route result = null;
        try {
            //Byte tmp = routeCache.ceilingKey(ToS);
            Map<RouteId, Route> curCache = routeCache.get(routeCache.floorKey(ToS));    //对于不准确的ToS，查找最相邻的
            //if (curCache.containsKey(id)) {
            result = curCache.get(id);
            if (log.isTraceEnabled()) {
                log.trace("getRoute: {} -> {}", id, result);
            }
            return result;
           // }
        }catch (Exception e){
            log.warn("Route not found");
            return null;
        }
    }

    @Override
    public Route getRoute(long src, long dst, long cookie) {
        return getRoute(src, dst, cookie, true);
    }

    @Override
    public Route getRoute(long src, long dst, long cookie, boolean tunnelEnabled) {
        //tunnelEnabled和cookie未使用
        return getRoute(src, dst, cookie, (byte)0, true);    //默认最低级别
    }

    @Override
    public Route getRoute(long srcId, short srcPort, long dstId, short dstPort, long cookie) {
        return getRoute(srcId, srcPort, dstId, dstPort, cookie, (byte)0, true);//默认最低级别
    }

    @Override
    public Route getRoute(long srcId, short srcPort, long dstId, short dstPort, long cookie, boolean tunnelEnabled) {
        return getRoute(srcId, srcPort, dstId, dstPort, cookie, (byte)0, true);
    }

    @Override
    public ArrayList<Route> getRoutes(long longSrcDpid, long longDstDpid, boolean tunnelEnabled) {
        return null;
    }

    @Override
    public boolean routeExists(long src, long dst) {
        return false;
    }

    @Override
    public boolean routeExists(long src, long dst, boolean tunnelEnabled) {
        return false;
    }


    @Override
    public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
        if(msg.getType()==OFType.PACKET_IN&&cntx!=null) {
            IDevice dstDevice = IDeviceService.fcStore.
                            get(cntx, IDeviceService.CONTEXT_DST_DEVICE);
            IDevice srcDevice = IDeviceService.fcStore.
                            get(cntx, IDeviceService.CONTEXT_SRC_DEVICE);
            if (dstDevice != null&&dstDevice.getIPv4Addresses()!=null&&dstDevice.getIPv4Addresses().length!=0) {
                try {
                    Integer[] IPs = dstDevice.getIPv4Addresses();
                    SwitchPort[] Daps = dstDevice.getAttachmentPoints();
                    if(IPs!=null&&IPs.length>0&&Daps!=null&&Daps.length>0) {
                        attachmentMap.put(IPs[0], Daps[0]);
                    }
                }catch (Exception e){
                    log.error("dst Device error");
                }
            }

            if (srcDevice != null&&srcDevice.getIPv4Addresses()!=null&&srcDevice.getIPv4Addresses().length!=0) {
                try {
                    Integer[] IPs = srcDevice.getIPv4Addresses();
                    SwitchPort[] Daps = srcDevice.getAttachmentPoints();
                    if (IPs != null && IPs.length>0 && Daps != null && Daps.length>0) {
                        attachmentMap.put(IPs[0], Daps[0]);
                    }
                }catch(Exception e){
                    log.error("src Device error");
                }
            }
        }
        return Command.CONTINUE;
    }

    @Override
    public String getName() {
        return "RouteByToS";
    }

    public Map<Integer, SwitchPort> getAttachmentMap() {
        return attachmentMap;
    }

    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) {
        return (type.equals(OFType.PACKET_IN) &&
                (name.equals("topology") ||
                        name.equals("devicemanager")));
    }

    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) {
        return false;
    }
}
