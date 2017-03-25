package io.github.agileluo.smartroute;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.framework.recipes.cache.NodeCacheListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ZoneAvoidanceRule;

/**
 * 
 * 智能路由规则
 * 
 * <p>
 * 提供以下功能
 * <ol>
 * <li>本地开发支持， 调用端配置debug.local=true，则优先调用本地服务</li>
 * <li>开发调试支持： 请求Header设置debugServerIp=开发机器ip， 则优先调用指定开发机器</li>
 * <li>动态支持： 包括A/B,灰度发布，支持ip段，指定用户等</li>
 * <li>默认规则： 支持跨中心的动态负载ZoneAvoidanceRule实现</li>
 * </ol>
 * <p>
 * 
 * 动态规则：
 * <p>
 * 1，路由规则
 * (type:[ab|gray|test|debug]).(typeInfo:[{pubVersion}|{ip}])=(services:({serviceId}:{serviceIp},)+)
 * <= (clients:<= [user|ip]:[{userId}|{clientIp}])?<br> 2， ab切换：
 * switch.ab.{pubVersion}=[a|b]
 * <p>
 * 例子1: <br>
 * ab.20170325-1=auth:192.168.4.3 <= ip:10.12.32.21<br>
 * switch.ab.20170325-1=a<br>
 * 表示: 发布ab版本20170325-1，
 * 客户端ip为10.12.32.21的auth请求会导流到192.168.4.3节点,其它auth请求不会导流至此节点<br>
 * 切换状态：switch.ab.20170325-1=b，表示ip为10.12.32.21的请求导流到192.168.4.3之外的节点，
 * 其它auth请求导流到192.168.4.3节点
 * <p>
 * 例子2: gray.20170325-1=auth:192.168.4.3,auth:192.168.5.4 <=
 * user:1234,ip:10.12.32.21<br>
 * 表示: 灰度版本20170325-1， 发布auth服务，包括两台机器192.168.4.3,192.168.5.4,
 * userId为1234或ip为10.12.32.21的auth请求会导流到这两个节点，
 * <font color="red">其它请求不会导流至此节点</font>
 * <p>
 * 例子3：test.192.168.23.4=auth:192.168.23.5,order:192.168.23.4<br>
 * 表示: 机器192.168.23.4处理测试模式下， auth服务只会导流至192.168.23.5,
 * order服务只会导流至192.168.23.4，其它请求规则不变
 * <p>
 * 例子4：debug.192.168.23.4=auth:192.168.23.5<br>
 * 表示: 机器192.168.23.4处理调试模式下， auth请求会导流至192.168.23.5，
 * <font color="red">其它机器auth请求不会导流至此节点</font>
 * <p>
 * 规则字段说明如下
 * <ol>
 * <li>type: gray-灰度发布， test-测试模式， debug-调试模式</li>
 * <li>typeInfo: pubVersion: 发布版本号, ip:测试或开发机器ip</li>
 * <li>services: 服务列表， serviceId: 服务id, ip: 服务对应的ip</li>
 * <li>clients：客户端列表, user:{userId}-指定userId的请求， ip:{ip}-指定ip的请求</li>
 * <li>serviceIp：服务ip地址，以*结尾表示通配地址</li>
 * <li>clientIp：客户端ip地址，以*结尾表示通配地址</li>
 * </ol>
 * 
 * <p>
 * 路由规则优先级 debug.local=true > 请求Header配置debugServerIp > debug > test > ab > gray
 * 
 * <p>
 * <font color="red">其它注意事项：配置项去除后，规则自动失效，将会使用默认动态负载实现</font>
 * 
 * 
 * @author marlon.luo
 * @version 1.0.0
 *
 */
@Component
public class SmartRule extends ZoneAvoidanceRule implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(SmartRule.class);

	@Value("${debug.local}")
	private boolean debugLocal;

	@Autowired
	private CuratorFramework client;

	private Set<String> localHosts = new HashSet<>();
	private NodeCache cache;

	@Override
	public Server choose(Object key) {
		// 开发调试支持
		String debugHost = MDC.get("debugHost");
		if (debugHost != null) {
			ILoadBalancer lb = getLoadBalancer();
			List<Server> servers = lb.getAllServers();
			for (Server s : servers) {
				if (debugHost.equals(s.getHost())) {
					return s;
				}
			}
		} // 本地开发支持
		else if (debugLocal) {
			ILoadBalancer lb = getLoadBalancer();
			List<Server> servers = lb.getAllServers();
			for (Server s : servers) {
				if (localHosts.contains(s.getHost())) {
					return s;
				}
			}
		} else {
			// A/B测试支持，灰度支持
		}
		return super.choose(key);
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		// 配置本地ip
		InetAddress addr = InetAddress.getLocalHost();
		String hostName = addr.getHostName();
		if (hostName.length() > 0) {
			InetAddress[] addrs = InetAddress.getAllByName(hostName);
			if (addrs.length > 0) {
				for (int i = 0; i < addrs.length; i++) {
					localHosts.add(addrs[i].getHostAddress());
				}
			}
		}
		//远程路由配置
		cache = new NodeCache(client, SmartRuleConstant.CONFIG_PATH);
		cache.start(true);
		cache.getListenable().addListener(new NodeCacheListener() {
			@Override
			public void nodeChanged() throws Exception {
				ChildData data = cache.getCurrentData();
				log.info("路由配置信息变更，重新加载远程路由信息");
				Properties p = loadRemoteProperties(data.getData());
			}
		});
	}

	private Properties loadRemoteProperties(byte[] data) throws IOException {
		String config = new String(data, "UTF-8");
		Properties p = new Properties();
		p.load(new StringReader(config));
		return p;
	}
}