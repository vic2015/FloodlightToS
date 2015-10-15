package net.floodlightcontroller.loadbalancer.energySaving;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPortMod;
import org.openflow.protocol.OFType;
import org.openflow.protocol.Wildcards;
import org.openflow.protocol.Wildcards.Flag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.SingletonTask;
import net.floodlightcontroller.linkCostService.ILinkCostService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.mst.Mst;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.threadpool.IThreadPoolService;

public class EnergySavingBaseOnMst implements IFloodlightModule,
		IFloodlightService {

	private IFloodlightProviderService floodlightProvider;
	private IThreadPoolService threadPool;
	private ILinkDiscoveryService linkDiscoveryManager;
	private Mst mst;
	private ILinkCostService linkCostService;
	private SingletonTask newInstanceTask;
	protected static Logger log = LoggerFactory
			.getLogger(EnergySavingBaseOnMst.class);
	// 网络的完整拓扑（网络节能前）
	private Map<Long, Set<Link>> wholeTopology;
	private int linkNumberFull;  //网络完整拓扑的链路数量
	private Map<Long, Set<Link>> currentTopology;
	private int linkNumber; // 当前拓扑的链路数量
	

	private Map<Link, Integer> overloadLinks = null; // value值保存的是该link被打开的次数
	// 链路权重
	private Map<Link, Integer> linkCostEnergySaving;
	private Integer threshold = 9;

	// 动态调整阈值相关变量
	private int linkNumberDynamic; // 超过当前设定阈值的链路数
	private int costDynamic;
	private int count=0; //设定更新阈值的次数，设定为过三个周期后进行更新阈值
	
	int runCount = 0;
	
	//
	private List<Link> haveSetUpLinks = new ArrayList<Link>();
	
	/**
	 * getter of haveSetUpLinks
	 * @return
	 */
	public List<Link> getHaveSetUpLinks(){
		return this.haveSetUpLinks;
	}
	/**
	 * 检测网络链路权重，返回链路权重大于设定阈值的链路
	 * 
	 * @param linkCost
	 * @return
	 */
	public Link detectLinkWeight(Map<Link, Integer> linkCost) {

		List<Map.Entry<Link, Integer>> entryList = this
				.getSortedLinkCost(linkCost);
		if (entryList.size() > 0) {
			log.info("maxWeight:{}", entryList.get(0).getValue());
		}

		// 获取超过当前阈值的链路数
		for (int i = 0; i < entryList.size(); i++) {
			Map.Entry<Link, Integer> entry = entryList.get(i);
			Integer cost = entry.getValue();
			if (cost > threshold) {
				linkNumberDynamic++;
				costDynamic = costDynamic + cost;
			}
		}
		log.info("threshold:{}",threshold);
		log.info("beyond the threshold link is:{}",linkNumberDynamic);
		// 动态调整阈值
		// 如果当前的超过阈值的链路数超过了总链路数的30%是，就动态进行调整阈值
		// 调整策略时，逐渐降低平均值的20%
		if ((linkNumberDynamic > entryList.size() / 10) && threshold >0) {
			if ( count >= 3){
				threshold = threshold - (costDynamic / linkNumberDynamic) * 2 / 10;
				threshold = 0;
				log.info("Adjust threshold to: " + threshold);
				count = 0;
			}else{
				count++;
			}
			
		}
		
		for (int i = 0; i < entryList.size(); i++) {
			Map.Entry<Link, Integer> entry = entryList.get(i);
			Link link = entry.getKey();
			Integer cost = entry.getValue();
			if (cost > threshold) {
				if (!overloadLinks.containsKey(link)) {
					overloadLinks.put(link, 1);
					return link;
				} else if (overloadLinks.get(link) < 2) { // 当一个权重大于阈值的链路被进行两次返回，则跳过不再对其进行返回
					Integer count = overloadLinks.get(link);
					overloadLinks.put(link, count + 1);
					return link;
				} else {
					overloadLinks.remove(link);
				}
			}
		}
		return null;
	}

	/**
	 * 批量获取大于阈值的链路 注:为了防止过量的大于阈值的链路被返回，本方法只是获取一半的过载链路，从而平衡链路利用率和节能效果
	 * 
	 * @param linkCost
	 * @return
	 */
	public List<Link> batchDetectLinkWeight(Map<Link, Integer> linkCost) {

		List<Map.Entry<Link, Integer>> entryList = this
				.getSortedLinkCost(linkCost);
		List<Link> localOverloadLinks = new ArrayList<Link>();

		if (entryList.size() > 0) {
			log.info("【batch】maxWeight:{}", entryList.get(0).getValue());
		}
		//初始化变量
		linkNumberDynamic = 0;
		costDynamic = 0;
		// 获取超过当前阈值的链路数
		for (int i = 0; i < entryList.size(); i++) {
			Map.Entry<Link, Integer> entry = entryList.get(i);
			Integer cost = entry.getValue();
			if (cost > threshold) {
				linkNumberDynamic++;
				costDynamic = costDynamic + cost;
			}
		}
		log.info("threshold:{}",threshold);
		log.info("beyond the threshold link is:{}",linkNumberDynamic);
		// 动态调整阈值
		// 如果当前的超过阈值的链路数超过了总链路数的30%是，就动态进行调整阈值
		// 调整策略时，逐渐降低平均值的20%
		if ((linkNumberDynamic > entryList.size() / 10) && threshold > 1) {
			
			threshold = threshold - (costDynamic / linkNumberDynamic) / 10;

			if( threshold < 1 ){
				threshold = 1;
			}
			log.info("Adjust threshold to: " + threshold);
				
		}
		

		for (int i = 0; i < entryList.size(); i++) {
			Map.Entry<Link, Integer> entry = entryList.get(i);
			Link link = entry.getKey();
			Integer cost = entry.getValue();
			if (cost > threshold) {
				localOverloadLinks.add(link); // 只要是大于阈值的链路都会返回，即使可能出现某天链路被打开多次
			}
		}
		
		return localOverloadLinks;
		
		/*if (localOverloadLinks.size() <= 1) {
			return localOverloadLinks;
		} else {
			int size = localOverloadLinks.size();
			int halfSize = size / 2;
			return localOverloadLinks.subList(0, halfSize);
		}*/

	}

	/**
	 * 对linkCost进行正序的排序
	 * 
	 * @param linkCost
	 * @return
	 */
	public List<Map.Entry<Link, Integer>> getSortedLinkCost(
			Map<Link, Integer> linkCost) {
		Set<Map.Entry<Link, Integer>> entrySet = linkCost.entrySet();
		List<Map.Entry<Link, Integer>> entryList = new ArrayList<Map.Entry<Link, Integer>>(
				entrySet);
		Collections.sort(entryList, new Comparator<Map.Entry<Link, Integer>>() {
			public int compare(Map.Entry<Link, Integer> m1,
					Map.Entry<Link, Integer> m2) {
				return -(m1.getValue().compareTo(m2.getValue()));
			}
		});
		return entryList;
	}

	/**
	 * 返回拥塞链路的环装替代链路，该方法假设拥塞链路是无向链路
	 * 
	 * @param wholeTopology
	 * @param currentTopology
	 * @param overloadLink
	 * @return
	 */
	public Link getLoopLinkNonBaseDirected(Link overloadLink,
			Map<Long, Set<Link>> wholeTopology,
			Map<Long, Set<Link>> currentTopology) {
		Link selectedLink = getLoopLinkBaseDirected(overloadLink,
				wholeTopology, currentTopology);
		if (selectedLink == null) {
			Link link = findSelectedLink(wholeTopology,currentTopology,overloadLink.getDst(),
					overloadLink.getSrc(),1);
			return getLoopLinkBaseDirected(link, wholeTopology, currentTopology);
		} else {
			return selectedLink;
		}
	}

	/**
	 * 返回拥塞链路的环装替代链路，该方法假设拥塞链路是有向链路
	 * 
	 * @param overloadLink
	 * @param wholeTopology
	 * @param currentTopology
	 * @return
	 */
	public Link getLoopLinkBaseDirected(Link overloadLink,
			Map<Long, Set<Link>> wholeTopology,
			Map<Long, Set<Link>> currentTopology) {

		int count = currentTopology.size();
		if (overloadLink == null) {
			return null;
		}
		long src = overloadLink.getSrc();
		long dst = overloadLink.getDst();
		boolean[] visited = new boolean[count + 1];
		for (int i = 0; i <= count; i++) {
			visited[i] = false;
		}
		PriorityBlockingQueue<Long> queue = new PriorityBlockingQueue<Long>();
		queue.add(dst);
		Link tempLink = null;
		Link selectedLink = null;
		while (!queue.isEmpty()) {
			long dpid = queue.poll();
			Set<Link> links = currentTopology.get(dpid);
			Iterator<Link> iter = links.iterator();
			while (iter.hasNext()) {
				tempLink = iter.next();
				long tempDst = tempLink.getDst();
				if (generalLinkEquals(tempLink, overloadLink)) {
					continue;
				}
				selectedLink = findSelectedLink(wholeTopology, currentTopology,tempDst, src,2);
				if (selectedLink != null) {
					if (!this.generalLinkEquals(overloadLink, selectedLink)) {
						return selectedLink;
					}
				}
				if (!visited[(int) tempDst]) {
					visited[(int) tempDst] = true;
					queue.add(tempDst);
				}
			}
		}
		return null;
	}

	/**
	 * 从给定拓扑中找到一条和指定源节点、目的节点一致的链路
	 * 
	 * @param wholeTopology
	 * @param src
	 * @param dst
	 * @param type type为1 表示从完整拓扑中获取给定src和dst的链路，type为2，表示从完整拓扑中获取给定的src和dst的链路，但是链路不能在当前的拓扑中
	 * @return
	 */
	public Link findSelectedLink(Map<Long, Set<Link>> wholeTopology,Map<Long, Set<Link>> currentTopology,
			long src,long dst,int type) {
		if(type == 1){
			Set<Link> links = wholeTopology.get(src);
			Iterator<Link> iterator = links.iterator();
			while (iterator.hasNext()) {
				Link link = iterator.next();
				if (link.getDst() == dst) {
					return link;
				}
			}
		}else{
			Set<Link> links = wholeTopology.get(src);
			for( Link link:links){
				if(link.getDst() == dst && !currentTopology.get(link.getSrc()).contains(link)){
					return link;
				}
			}
			
		}
		
		return null;
	}

	/**
	 * 判断两条有向链路是否隶属于同一条无向链路，供getLoopLinkBaseDirected使用
	 * 
	 * @param link1
	 * @param link2
	 * @return
	 */
	public boolean generalLinkEquals(Link link1, Link link2) {
		long src = link1.getSrc();
		short srcPort = link1.getSrcPort();
		long dst = link1.getDst();
		short dstPort = link1.getDstPort();
		Link reverseLink = new Link(dst, dstPort, src, srcPort);
		if (link1.equals(link2) || reverseLink.equals(link2)) {
			return true;
		}
		return false;
	}

	/**
	 * 完成对网络拓扑信息的复制, 将网络的初始拓扑保存下来
	 */
	public void copySwitchLinks() {
		currentTopology = new HashMap<Long, Set<Link>>();
		linkNumber = 0; // 重新对linkNumber进行赋值，故对其做初始化操作；
		Map<Long, Set<Link>> switchLinks = linkDiscoveryManager
				.getSwitchLinks();
		Set<Long> keys = switchLinks.keySet();
		Iterator<Long> iter1 = keys.iterator();
		while (iter1.hasNext()) {
			Long key = iter1.next();
			Set<Link> links = switchLinks.get(key);
			Set<Link> srcLink = new HashSet<Link>();
			Iterator<Link> iter2 = links.iterator();
			while (iter2.hasNext()) {
				Link link = new Link();
				link = iter2.next();
				if (key.equals(link.getSrc())) {
					srcLink.add(link);
					linkNumber++;
				}
			}
			currentTopology.put(key, srcLink);
		}
		log.info("EnergySavingBaseOnMst.copySwitchLinks linkNumber {}",
				linkNumber);
	}

	public boolean setLinkUp(Link link) {
		short portNumber = link.getDstPort();
		long dpid = link.getDst();
		IOFSwitch ofs = floodlightProvider.getSwitch(dpid);
		if (ofs != null && setPortUp(ofs, portNumber) &&
				setPortUp(floodlightProvider.getSwitch(link.getSrc()),link.getSrcPort())) {
			log.info("【link up】EnergySavingBaseOnMst.setLinkUp {} up", link);
			return true;
		}
		return false;
	}

	public boolean setPortUp(IOFSwitch ofs, short portNumber) {
		// 获得OpenFlow交换机的某个端口的物理地址
		ImmutablePort ofpPort = ofs.getPort(portNumber);
		if (ofpPort == null)
			return false;
		byte[] macAddress = ofpPort.getHardwareAddress();
		// 定义OFPortMod命令
		OFPortMod mymod = (OFPortMod) floodlightProvider.getOFMessageFactory()
				.getMessage(OFType.PORT_MOD);
		// 设置OFPortMod命令的相关参数
		mymod.setPortNumber(portNumber);
		// mymod.setConfig(0); 开启某个端口
		
		mymod.setConfig(0);
		mymod.setHardwareAddress(macAddress);
		mymod.setMask(0xffffffff);
		// 将OFPortMod命令发送到指定的交换机中，进行执行！
		try {
			ofs.write(mymod, null);
			ofs.flush();
		} catch (Exception e) {
			log.error("link up fail");
			return false;
		}
		return true;
	}

	public void deleteFlowEntry(long dpid, short portNumber) {
		IOFSwitch sw = floodlightProvider.getSwitch(dpid);
		OFMatch match = new OFMatch();
		match.setWildcards(Wildcards.FULL.matchOn(Flag.TP_DST));
		match.setNetworkProtocol(IPv4.PROTOCOL_UDP);
		match.setTransportDestination((short) 5001);
		OFFlowMod ofFlowMod = (OFFlowMod) floodlightProvider
				.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
		ofFlowMod.setMatch(match);
		ofFlowMod.setCommand(OFFlowMod.OFPFC_DELETE);
		ofFlowMod.setOutPort(portNumber);

		try {
			sw.write(ofFlowMod, null);
			sw.flush();
			log.info(
					"EnergySavingBaseOnMst.deleteFlowEntry Dpid: {} portNumber: {}",
					new Object[] { sw.getId(), portNumber });
		} catch (Exception e) {
			log.error(
					"EnergySavingBaseOnMst.deleteFlowEntry error Dpid: {} portNumber: {}",
					new Object[] { sw.getId(), portNumber });
		}
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context
				.getServiceImpl(IFloodlightProviderService.class);
		threadPool = context.getServiceImpl(IThreadPoolService.class);
		mst = context.getServiceImpl(Mst.class);
		linkCostService = context.getServiceImpl(ILinkCostService.class);
		linkDiscoveryManager = context
				.getServiceImpl(ILinkDiscoveryService.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		overloadLinks = new HashMap<Link, Integer>();
		ScheduledExecutorService ses = threadPool.getScheduledExecutor();
		newInstanceTask = new SingletonTask(ses, new Runnable() {
			public void run() {
				try {
					runCount = runCount + 1;
					wholeTopology = mst.getWholeTopology();
					linkNumberFull = mst.getLinkNumberFull();
					copySwitchLinks(); // 保存当前网络的拓扑到currentTopology；
					if(runCount > 20){
						Set<Long> keyset = currentTopology.keySet();
						for(Long id:keyset){
							Set<Link> links = currentTopology.get(id);
							log.info("switch dpid:{}",id);
							for(Link link:links){
								log.info("link:{}",link);
							}
						}
					}
					if( linkNumberFull == linkNumber ){
						log.info("链路已经全部开启！！！");
					}else{
						linkCostEnergySaving = linkCostService.getLinkCostEnergySaving();
						List<Link> overloadLinks = batchDetectLinkWeight(linkCostEnergySaving);
						boolean type = true;
						if (type) { // 批量开启关闭的链路
							long startTime = System.currentTimeMillis();
							if (overloadLinks != null && overloadLinks.size() > 0) {
								for (Link link : overloadLinks) {
									log.info("拥塞链路：{}", link);
									Link loopLink = getLoopLinkNonBaseDirected(
											link, wholeTopology, currentTopology);
									log.info("【LoopLink】loopLink: {}",loopLink);
									
									if (loopLink != null  ) {
										if(!haveSetUpLinks.contains(loopLink)){
											log.info("loopLink set up:{}", loopLink);
											haveSetUpLinks.add(loopLink);
											setLinkUp(loopLink);
										}else{
											log.info("set up loopLink again: {}",loopLink);
											setLinkUp(loopLink);
										}
										Set<Link> linkSet = currentTopology.get(loopLink.getSrc());
										linkSet.add(loopLink);
										currentTopology.put(loopLink.getSrc(), linkSet);
										Link reverseLink = new Link(loopLink.getDst(), loopLink.getDstPort(), loopLink.getSrc(), loopLink.getSrcPort());
										Set<Link> linkSet2 = currentTopology.get(loopLink.getDst());
										linkSet2.add(reverseLink);
										currentTopology.put(loopLink.getDst(), linkSet2);
										
										deleteFlowEntry(link.getSrc(),
												link.getSrcPort()); // 这里必须删除当前所关联的两个交换机上的流表
										deleteFlowEntry(link.getDst(),
												link.getDstPort());
									}
									
								}
							}
							long endTime = System.currentTimeMillis();
							long time = (endTime - startTime);
							log.info("【Batch】:time to set links up:{} ms", time);
						} 
					}
					
				} catch (Exception e) {
					log.error("exception", e);
				} finally {
					newInstanceTask.reschedule(15, TimeUnit.SECONDS);
				}
			}
		});
		
		newInstanceTask.reschedule(20, TimeUnit.SECONDS);
	}

}
