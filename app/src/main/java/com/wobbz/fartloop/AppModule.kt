package com.wobbz.fartloop

import com.wobbz.fartloop.feature.rules.model.RealRuleEvaluator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing application-level dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    /**
     * Provide RuleEvaluator implementation.
     * Updated to use real rule evaluator now that Team B has implemented the visual rule builder.
     */
    @Binds
    @Singleton
    abstract fun bindRuleEvaluator(impl: RealRuleEvaluator): RuleEvaluator
}
