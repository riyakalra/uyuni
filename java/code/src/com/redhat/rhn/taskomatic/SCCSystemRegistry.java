package com.redhat.rhn.taskomatic;

import com.redhat.rhn.domain.credentials.Credentials;
import com.redhat.rhn.domain.product.SUSEProduct;
import com.redhat.rhn.domain.scc.SCCCachingFactory;
import com.redhat.rhn.domain.scc.SCCRegCacheItem;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerFactory;
import com.redhat.rhn.manager.content.ContentSyncManager;

import com.suse.scc.client.SCCClientException;
import com.suse.scc.client.SCCConfig;
import com.suse.scc.client.SCCWebClient;
import com.suse.scc.model.SCCMinProductJson;
import com.suse.scc.model.SCCRegisterSystemJson;
import com.suse.scc.model.SCCSystemCredentialsJson;
import com.suse.utils.Opt;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.log4j.Logger;

import java.net.URI;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SCCSystemRegistry {

    private final Logger LOG = Logger.getLogger(SCCSystemRegistry.class);
    private final URI sccURI;
    private final String uuid;

    public SCCSystemRegistry(URI sccURIIn, String uuidIn) {
        this.sccURI = sccURIIn;
        this.uuid = uuidIn;
    }

    public void deregister(List<SCCRegCacheItem> items, boolean forceDBDeletion) {
        items.forEach(cacheItem -> {
            cacheItem.getOptSccId().ifPresentOrElse(
                    sccId -> {
                        Credentials itemCredentials = cacheItem.getOptCredentials().get();
                        SCCWebClient sccClient = new SCCWebClient(new SCCConfig(
                                sccURI,
                                itemCredentials.getUsername(),
                                itemCredentials.getPassword(),
                                uuid
                        ));
                        try {
                            LOG.debug("de-register system " + cacheItem);
                            sccClient.deleteSystem(sccId);
                            SCCCachingFactory.deleteRegCacheItem(cacheItem);
                        }
                        catch (SCCClientException e) {
                            LOG.error("Error deregistering system " + cacheItem.getId(), e);
                            if (forceDBDeletion || e.getHttpStatusCode() == 404) {
                                SCCCachingFactory.deleteRegCacheItem(cacheItem);
                            }
                        }
                    },
                    () -> {
                        LOG.debug("delete not registered cache item " + cacheItem);
                        SCCCachingFactory.deleteRegCacheItem(cacheItem);
                    }
            );
        });
    }

    public void register(List<SCCRegCacheItem> items, Credentials primaryCredential) {
        items.forEach(cacheItem -> {
            try {
                Credentials itemCredentials = cacheItem.getOptCredentials().orElse(primaryCredential);
                SCCWebClient sccClient = new SCCWebClient(new SCCConfig(
                        sccURI,
                        itemCredentials.getUsername(),
                        itemCredentials.getPassword(),
                        uuid
                ));
                LOG.debug("Forward registration of " + cacheItem);
                SCCSystemCredentialsJson systemCredentials = sccClient.createSystem(getPayload(cacheItem));
                cacheItem.setSccId(systemCredentials.getId());
                cacheItem.setSccLogin(systemCredentials.getLogin());
                cacheItem.setSccPasswd(systemCredentials.getPassword());
                cacheItem.setSccRegistrationRequired(false);
                cacheItem.setRegistrationErrorTime(null);
                cacheItem.setCredentials(itemCredentials);
            }
            catch (SCCClientException e) {
                LOG.error("Error registering system " + cacheItem.getId(), e);
                cacheItem.setRegistrationErrorTime(new Date());
            }
            cacheItem.getOptServer().ifPresent(ServerFactory::save);
        });
    }

    private SCCRegisterSystemJson getPayload(SCCRegCacheItem rci) {
        Server srv = rci.getOptServer().get();
        List<SCCMinProductJson> products = Opt.fold(srv.getInstalledProductSet(),
                () -> {
                    return new LinkedList<SUSEProduct>();
                },
                s -> {
                    List<SUSEProduct> prd = new LinkedList<>();
                    prd.add(s.getBaseProduct());
                    prd.addAll(s.getAddonProducts());
                    return prd;
                }
        ).stream()
                .map(p -> new SCCMinProductJson(p))
                .collect(Collectors.toList());

        Map<String, String> hwinfo = new HashMap<>();
        Optional.ofNullable(srv.getCpu().getNrCPU()).ifPresent(c -> hwinfo.put("cpus", c.toString()));
        Optional.ofNullable(srv.getCpu().getNrsocket()).ifPresent(c -> hwinfo.put("sockets", c.toString()));
        hwinfo.put("arch", srv.getServerArch().getLabel().split("-")[0]);
        if (srv.isVirtualGuest()) {
            hwinfo.put("hypervisor", srv.getVirtualInstance().getType().getHypervisor().orElse(""));
            hwinfo.put("cloud_provider", srv.getVirtualInstance().getType().getCloudProvider().orElse(""));
            Optional.ofNullable(srv.getVirtualInstance().getUuid()).ifPresent(uuid -> {
                hwinfo.put("uuid", uuid.replaceAll("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                        "$1-$2-$3-$4-$5"));
            });
        }
        else {
            // null == physical instance
            hwinfo.put("hypervisor", null);
        }

        String login = rci.getOptSccLogin().orElseGet(() -> {
            String l = String.format("%s-%s", ContentSyncManager.getUUID(), srv.getId().toString());
            rci.setSccLogin(l);
            SCCCachingFactory.saveRegCacheItem(rci);
            return l;
        });
        String passwd = rci.getOptSccPasswd().orElseGet(() -> {
            String pw = RandomStringUtils.randomAlphanumeric(64);
            rci.setSccPasswd(pw);
            SCCCachingFactory.saveRegCacheItem(rci);
            return pw;
        });

        return new SCCRegisterSystemJson(login, passwd, srv.getHostname(), hwinfo, products);
    }

}
