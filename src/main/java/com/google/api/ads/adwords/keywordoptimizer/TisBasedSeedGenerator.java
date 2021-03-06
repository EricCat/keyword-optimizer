// Copyright 2016 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.api.ads.adwords.keywordoptimizer;

import com.google.api.ads.adwords.axis.v201607.cm.ApiException;
import com.google.api.ads.adwords.axis.v201607.cm.KeywordMatchType;
import com.google.api.ads.adwords.axis.v201607.cm.Paging;
import com.google.api.ads.adwords.axis.v201607.o.Attribute;
import com.google.api.ads.adwords.axis.v201607.o.AttributeType;
import com.google.api.ads.adwords.axis.v201607.o.StringAttribute;
import com.google.api.ads.adwords.axis.v201607.o.TargetingIdea;
import com.google.api.ads.adwords.axis.v201607.o.TargetingIdeaPage;
import com.google.api.ads.adwords.axis.v201607.o.TargetingIdeaSelector;
import com.google.api.ads.adwords.axis.v201607.o.TargetingIdeaService;
import com.google.api.ads.adwords.axis.v201607.o.TargetingIdeaServiceInterface;
import com.google.api.ads.common.lib.utils.Maps;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import java.rmi.RemoteException;
import java.util.Map;
import java.util.Set;

/**
 * Base class for {@link SeedGenerator}s using the {@link TargetingIdeaService} for creating seed
 * keywords. Delegates the creation of the {@link TargetingIdeaSelector} to derived classes and
 * implements the extraction of plain text keywords from the results of the
 * {@link TargetingIdeaService}.
 */
public abstract class TisBasedSeedGenerator extends AbstractSeedGenerator {
  // Page size for retrieving results. All pages are used anyways (not just the first one), so
  // using a reasonable value here.
  public static final int PAGE_SIZE = 100;
  
  protected TargetingIdeaServiceInterface tis;
  private final Long clientCustomerId;

  /**
   * Creates a new {@link TisBasedSeedGenerator} based on the given service and customer id.
   *
   * @param tis the API interface to the TargetingIdeaService
   * @param clientCustomerId the AdWords customer ID
   * @param matchTypes match types to be used for seed keyword creation
   * @param campaignConfiguration additional campaign-level settings for keyword evaluation
   */
  public TisBasedSeedGenerator(
      TargetingIdeaServiceInterface tis,
      Long clientCustomerId,
      Set<KeywordMatchType> matchTypes,
      CampaignConfiguration campaignConfiguration) {
    super(matchTypes, campaignConfiguration);
    this.tis = tis;
    this.clientCustomerId = clientCustomerId;
  }

  /**
   * @return returns a selector for the {@link TargetingIdeaService}
   */
  protected abstract TargetingIdeaSelector getSelector();
  
  @Override
  protected ImmutableMap<String, IdeaEstimate> getKeywordsAndEstimates()
      throws KeywordOptimizerException {
    final TargetingIdeaSelector selector = getSelector();
    Builder<String, IdeaEstimate> keywordsAndEstimatesBuilder = ImmutableMap.builder();
    
    try {
      int offset = 0;

      TargetingIdeaPage page = null;
      final AwapiRateLimiter rateLimiter =
          AwapiRateLimiter.getInstance(AwapiRateLimiter.RateLimitBucket.OTHERS);

      do {
        selector.setPaging(new Paging(offset, PAGE_SIZE));
        page = rateLimiter.run(new AwapiCall<TargetingIdeaPage>() {
          @Override
          public TargetingIdeaPage invoke() throws ApiException, RemoteException {
            return tis.get(selector);
          }
        }, clientCustomerId);

        if (page.getEntries() != null) {
          for (TargetingIdea targetingIdea : page.getEntries()) {
            Map<AttributeType, Attribute> attributeData = Maps.toMap(targetingIdea.getData());
            
            StringAttribute keywordAttribute =
                (StringAttribute) attributeData.get(AttributeType.KEYWORD_TEXT);
            IdeaEstimate estimate = KeywordOptimizerUtil.toSearchEstimate(attributeData);
            
            keywordsAndEstimatesBuilder.put(keywordAttribute.getValue(), estimate);
          }
        }
        offset += PAGE_SIZE;
      } while (offset < page.getTotalNumEntries());

    } catch (ApiException e) {
      throw new KeywordOptimizerException("Problem while querying the targeting idea service", e);
    } catch (RemoteException e) {
      throw new KeywordOptimizerException("Problem while connecting to the AdWords API", e);
    }

    return keywordsAndEstimatesBuilder.build();
  }
  
}
