package com.twshop.product.api

import com.twshop.product.application.ProductService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Product/Inventory에 대한 기본 CRUD API. */
@RestController
@RequestMapping("/api/products")
class ProductController(
    private val productService: ProductService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createProduct(@Valid @RequestBody request: CreateProductRequest): ProductResponse {
        val product = productService.createProduct(
            name = request.name,
            description = request.description,
            price = request.price,
            initialQuantity = request.initialQuantity,
        )
        return ProductResponse.from(product)
    }

    @GetMapping("/{productId}")
    fun getProduct(@PathVariable productId: Long): ProductResponse =
        ProductResponse.from(productService.getProduct(productId))

    @GetMapping
    fun listProducts(): List<ProductResponse> =
        productService.listProducts().map(ProductResponse::from)

    @GetMapping("/{productId}/inventory-summary")
    fun getInventorySummary(@PathVariable productId: Long): InventorySummaryResponse =
        InventorySummaryResponse.from(productService.getInventorySummary(productId))

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteProduct(@PathVariable productId: Long) {
        productService.deleteProduct(productId)
    }
}
