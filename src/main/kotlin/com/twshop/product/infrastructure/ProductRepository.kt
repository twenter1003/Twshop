package com.twshop.product.infrastructure

import com.twshop.product.domain.Product
import org.springframework.data.jpa.repository.JpaRepository

/** [Product]에 대한 기본 CRUD 접근을 제공한다. */
interface ProductRepository : JpaRepository<Product, Long>
